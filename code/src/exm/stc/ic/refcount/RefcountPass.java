package exm.stc.ic.refcount;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.CompilerBackend.RefCount;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.RefCounting;
import exm.stc.common.lang.RefCounting.RefCountType;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.StructType.StructField;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.lang.Var.DefType;
import exm.stc.common.lang.Var.VarProvenance;
import exm.stc.common.util.Counters;
import exm.stc.common.util.Pair;
import exm.stc.ic.opt.AliasTracker.AliasKey;
import exm.stc.ic.opt.OptimizerPass;
import exm.stc.ic.tree.Conditionals.Conditional;
import exm.stc.ic.tree.ForeachLoops.AbstractForeachLoop;
import exm.stc.ic.tree.ICContinuations.BlockingVar;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICContinuations.ContinuationType;
import exm.stc.ic.tree.ICContinuations.Loop;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICInstructions.LoopBreak;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.BlockType;
import exm.stc.ic.tree.ICTree.CleanupAction;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.Program;
import exm.stc.ic.tree.ICTree.Statement;
import exm.stc.ic.tree.ICTree.StatementType;
import exm.stc.ic.tree.Opcode;
import exm.stc.ic.tree.TurbineOp;
import exm.stc.ic.tree.TurbineOp.RefCountOp;
import exm.stc.ic.tree.TurbineOp.RefCountOp.RCDir;

/**
 * Eliminate, merge and otherwise reduce read/write reference counting
 * operations. Run as a post-processing step.
 * 
 * Additional unimplemented ideas:
 * - Pushing down reference decrements to blocks where they can be merged
 */
public class RefcountPass implements OptimizerPass {

  private Logger logger = null;

  static final List<RefCountType> RC_TYPES = Arrays.asList(
      RefCountType.READERS, RefCountType.WRITERS);
  /**
   * Map of names to functions, used inside pass. Must be initialized before
   * pass runs.
   */
  private Map<String, Function> functionMap = null;

  private RCPlacer placer = null;

  @Override
  public String getPassName() {
    return "Refcount adding";
  }

  @Override
  public String getConfigEnabledKey() {
    return null;
  }

  @Override
  public void optimize(Logger logger, Program program) throws UserException {
    this.logger = logger;

    functionMap = program.getFunctionMap();
    placer = new RCPlacer(functionMap);

    for (Function f: program.getFunctions()) {
      logger.trace("Entering function " + f.getName());
      lookupStructArgMembers(logger, f);
      recurseOnBlock(logger, f, f.mainBlock(), new RCTracker(),
          new TopDownInfo());
    }

    this.functionMap = null;
    this.placer = null;
  }

  private void recurseOnBlock(Logger logger, Function f, Block block,
      RCTracker increments, TopDownInfo parentInfo) {

    /*
     * Traverse in bottom-up order so that we can access refcount info in child
     * blocks for pulling up
     */

    // Make a child copy for first pass over this block
    TopDownInfo info = parentInfo.makeChild();
    for (Statement stmt: block.getStatements()) {
      switch (stmt.type()) {
        case INSTRUCTION: {
          // Add information that we'll want to know about in child blocks
          info.updateForInstruction(stmt.instruction());
          break;
        }
        case CONDITIONAL: {
          // Recurse on conditionals to add refcounts
          recurseOnCont(logger, f, stmt.conditional(), info);
          break;
        }
        default:
          throw new STCRuntimeError("Unknown type " + stmt.type());
      }
    }

    // Recurse on remaining continuations to add refcounts
    for (Continuation cont: block.getContinuations()) {
      recurseOnCont(logger, f, cont, info);
    }

    // Now add refcounts to this block
    processBlock(logger, f, block, increments, parentInfo);
  }

  private void recurseOnCont(Logger logger, Function f, Continuation cont,
      TopDownInfo info) {
    
    for (Block block: cont.getBlocks()) {

      // Workaround for e.g. foreach loops: lookup all struct members 
      lookupAllStructMembers(block, cont.constructDefinedVars());
      
      // Build separate copy for each block

      TopDownInfo contInfo = info.makeChild(cont);

      RCTracker increments = new RCTracker(contInfo.aliases);
      addDecrementsBlocksInsideCont(cont, increments);

      recurseOnBlock(logger, f, block, increments, contInfo);
    }
    // Do any additional work for continuation
    if (RCUtil.isForeachLoop(cont)) {
      postProcessForeachLoop(cont);
    }
  }

  /**
   * 
   * @param logger
   * @param fn
   * @param block
   * @param increments
   *          pre-existing decrements
   * @param parentAssignedAliasVars
   *          assign alias vars from parent blocks that we can immediately
   *          manipulate refcount of
   */
  private void processBlock(Logger logger, Function fn, Block block,
      RCTracker increments, TopDownInfo parentInfo) {
    // First collect up all required reference counting ops in block
    for (Var v: block.getVariables()) {
      if (v.mapping() != null) {
        // Add two since Turbine libraries want two references
        increments.readIncr(v.mapping(), 2);
      }
    }

    countBlockIncrements(block, increments);

    countBlockDecrements(fn, block, increments);

    if (RCUtil.cancelEnabled()) {
      // Second put saved refcounts back into IC
      placeRefcounts(logger, fn, block, increments,
          parentInfo.initAliasVars);
    }

    addTemporaryStructFields(block, increments, parentInfo);
  }

  /**
   * Handle foreach loop as special case where we want to increment <# of
   * iterations> * <needed increments inside loop> before loop.
   * 
   * This function will work out if it can pull out increments from foreach loop
   * body
   */
  private void postProcessForeachLoop(Continuation c) {
    AbstractForeachLoop loop = (AbstractForeachLoop) c;

    Counters<Var> readIncrs = new Counters<Var>();
    Counters<Var> writeIncrs = new Counters<Var>();

    if (loop.isAsync()) {
      // If we're spawning off, increment once per iteration so that
      // each parallel task has a refcount to work with
      for (PassedVar v: loop.getAllPassedVars()) {
        if (!v.writeOnly && RefCounting.hasReadRefCount(v.var)) {
          readIncrs.increment(v.var);
        }
      }
      for (Var v: loop.getKeepOpenVars()) {
        if (RefCounting.hasWriteRefCount(v)) {
          writeIncrs.increment(v);
        }
      }
    }

    // Now see if we can pull some increments out of top of block
    Block loopBody = loop.getLoopBody();
    ListIterator<Statement> it = loopBody.statementIterator();

    while (it.hasNext()) {
      Statement stmt = it.next();
      // stop processing once we get past the initial increments
      if (stmt.type() != StatementType.INSTRUCTION ||
          !RefCountOp.isIncrement(stmt.instruction().op)) {
        break;
      }

      Instruction inst = stmt.instruction();
      Arg amount = RefCountOp.getRCAmount(inst);
      Var var = RefCountOp.getRCTarget(inst);
      RefCountType rcType = RefCountOp.getRCType(inst.op);
      if (amount.isIntVal() && RCUtil.definedOutsideCont(loop, loopBody, var)) {
        // Pull up constant increments
        if (rcType == RefCountType.READERS) {
          readIncrs.add(var, amount.getIntLit());
        } else {
          assert (rcType == RefCountType.WRITERS);
          writeIncrs.add(var, amount.getIntLit());
        }
        it.remove();
      }
    }

    for (Entry<Var, Long> read: readIncrs.entries()) {
      loop.addStartIncrement(new RefCount(read.getKey(), RefCountType.READERS,
          Arg.createIntLit(read.getValue())));
    }

    for (Entry<Var, Long> write: writeIncrs.entries()) {
      loop.addStartIncrement(new RefCount(write.getKey(), RefCountType.WRITERS,
          Arg.createIntLit(write.getValue())));
    }
  }

  private void placeRefcounts(Logger logger, Function fn, Block block,
      RCTracker increments, Set<Var> parentAssignedAliasVars) {

    // First canonicalize so we can merge refcounts
    increments.canonicalize();

    if (logger.isTraceEnabled()) {
      logger.trace("");
      logger.trace("Adding increments for block " + block.getType() + " of " +
          fn.getName());
      logger.trace("==============================");
      logger.trace(increments);
    }
    
    reorderContinuations(logger, block);

    if (RCUtil.cancelEnabled()) {
      // Move any increment instructions up to this block
      // if they can be combined with increments here
      pullUpRefIncrements(block, increments);
    }

    placer.placeAll(logger, fn, block, increments, parentAssignedAliasVars);
  }

  /**
   * Work out how much each variable needs to be incremented.
   * 
   * In some cases this will place the increment instructions right away
   * 
   * @param block
   * @param increments
   */
  private void countBlockIncrements(Block block, RCTracker increments) {
    ListIterator<Statement> stmtIt = block.statementIterator();
    while (stmtIt.hasNext()) {
      Statement stmt = stmtIt.next();
      Instruction inst;
      switch (stmt.type()) {
        case INSTRUCTION:
          inst = stmt.instruction();
          updateCountsInstruction(inst, increments);
          increments.updateForInstruction(inst);
          break;
        case CONDITIONAL:
          inst = null;
          updateIncrementsPassIntoCont(stmt.conditional(), increments);
          break;
        default:
          throw new STCRuntimeError("Unknown statement type " + stmt.type());
      }

      if (!RCUtil.cancelEnabled()) {
        placer.dumpIncrements(inst, block, stmtIt, increments);
      }
    }
    for (Continuation cont: block.getContinuations()) {
      updateIncrementsPassIntoCont(cont, increments);
      if (!RCUtil.cancelEnabled()) {
        placer.dumpIncrements(null, block, block.statementEndIterator(),
            increments);
      }
    }
  }

  /**
   * Work out how much each variable needs to be decrementent.
   * 
   * In some cases this will place the decrement instructions right away
   * 
   * @param block
   * @param increments
   */
  private void countBlockDecrements(Function fn, Block block,
      RCTracker increments) {
    // If this is main block of function, add passed in
    if (block.getType() == BlockType.MAIN_BLOCK) {
      assert (block == fn.mainBlock());
      if (fn.isAsync()) {
        // Need to do bookkeeping if this runs in separate task
        for (Var i: fn.getInputList()) {
          increments.readDecr(i);
          if (Types.isScalarUpdateable(i) && RefCounting.WRITABLE_UPDATEABLE_INARGS) {
            increments.writeDecr(i);
          }
        }
        for (PassedVar o: fn.getPassedOutputList()) {
          increments.writeDecr(o.var);

          // Outputs might be read in function, need to maintain that
          // refcount
          if (!o.writeOnly) {
            increments.readDecr(o.var);
          }
        }
      }
    }

    for (Var var: block.getVariables()) {
      // Any variables allocated in block will need to be freed when
      // they go out of scope, so do initial decrement of reference counts.
      // Alias variables aren't allocated here. Struct variables have
      // members separately allocated, so don't want to double-decrement
      // struct members.
      if (var.storage() != Alloc.ALIAS && !Types.isStruct(var)) {
        increments.readDecr(var);
        increments.writeDecr(var);
      }
    }

    if (RCUtil.cancelEnabled()) {
      ListIterator<CleanupAction> caIt = block.cleanupIterator();
      while (caIt.hasNext()) {
        CleanupAction ca = caIt.next();
        Instruction action = ca.action();
        if (RefCountOp.isRefcountOp(action.op) &&
            RefCountOp.isDecrement(action.op)) {
          Var decrVar = RefCountOp.getRCTarget(action);
          Arg amount = RefCountOp.getRCAmount(action);
          if (amount.isIntVal()) {
            // Remove instructions where counts is integer value
            RefCountType rcType = RefCountOp.getRCType(action.op);
            increments.decr(decrVar, rcType, amount.getIntLit());
            caIt.remove();
          }
        }
      }
    }
    if (!RCUtil.cancelEnabled()) {
      placer.dumpDecrements(block, increments);
    }
  }

  private void updateCountsInstruction(Instruction inst, RCTracker increments) {
    Pair<List<Var>, List<Var>> refIncrs = inst.getIncrVars(functionMap);
    List<Var> readIncrVars = refIncrs.val1;
    List<Var> writeIncrVars = refIncrs.val2;

    for (Var v: readIncrVars) {
      increments.readIncr(v);
    }
    for (Var v: writeIncrVars) {
      increments.writeIncr(v);
    }

    if (logger.isTraceEnabled()) {
      logger.trace("At " + inst + " readIncr: " + readIncrVars +
          " writeIncr: " + writeIncrVars);
    }

    if (inst.op == Opcode.COPY_REF) {
      // Hack to handle COPY_REF
      // We incremented refcounts for orig. var, now need to decrement
      // refcount on alias vars
      Var newAlias = inst.getOutput(0);
      increments.readDecr(newAlias);
      increments.writeDecr(newAlias);
    }

    if (inst.op == Opcode.LOAD_REF) {
      // Hack to handle fact that the load_ref will increment
      // reference count of referand
      Var v = inst.getOutput(0);
      increments.readDecr(v);
    }

    if (inst.op == Opcode.LOOP_BREAK) {
      // Special case: decrement all variables passed into loop from outside
      LoopBreak loopBreak = (LoopBreak) inst;
      for (Var ko: loopBreak.getKeepOpenVars()) {
        assert (RefCounting.hasWriteRefCount(ko));
        increments.writeDecr(ko);
      }
      for (PassedVar pass: loopBreak.getLoopUsedVars()) {
        if (!pass.writeOnly) {
          increments.readDecr(pass.var);
        }
      }
    }
  }

  /**
   * Update increments for variables passed into continuation
   * 
   * @param cont
   * @param increments
   */
  private void updateIncrementsPassIntoCont(Continuation cont,
      RCTracker increments) {
    if (cont.spawnsSingleTask()) {
      long incr = 1;  // Increment once per task
      for (Var keepOpen: cont.getKeepOpenVars()) {
        assert (RefCounting.hasWriteRefCount(keepOpen));

        increments.writeIncr(keepOpen, incr);
      }

      // Avoid duplicate read increments
      Set<Var> readIncrTmp = new HashSet<Var>();

      for (PassedVar passedIn: cont.getAllPassedVars()) {
        if (!passedIn.writeOnly && RefCounting.hasReadRefCount(passedIn.var)) {
          readIncrTmp.add(passedIn.var);
        }
      }
      for (BlockingVar blockingVar: cont.blockingVars(false)) {
        if (RefCounting.hasReadRefCount(blockingVar.var)) {
          readIncrTmp.add(blockingVar.var);
        }
      }
      for (Var v: readIncrTmp) {
        increments.readIncr(v, incr);
      }
    } else if (RCUtil.isForeachLoop(cont)) {
      AbstractForeachLoop foreach = (AbstractForeachLoop) cont;
      updateIncrementsPassIntoForeach(increments, foreach);
    }
  }

  private void updateIncrementsPassIntoForeach(RCTracker increments,
      AbstractForeachLoop foreach) {
    /*
     * Hold a refcount until we get to this loop: do a switcheroo where 
     * we pull an increment into the outer block, and balance by adding
     * a decrement to loop.
     */
    if (foreach.isAsync()) {
      for (RefCount rc: foreach.getStartIncrements()) {
        increments.incr(rc.var, rc.type, 1);
        foreach.addConstantStartIncrement(rc.var, rc.type, Arg.createIntLit(-1));
      }
    }
  }

  /**
   * Create temporary variables to hold struct aliases
   * 
   * @param increments
   * @param parentAssignedAliasVars
   */
  private void addTemporaryStructFields(Block block, RCTracker increments,
                                        TopDownInfo parentInfo) {
    // Vars that were created out of order
    Set<Var> alreadyCreated = new HashSet<Var>();
    for (Var toCreate: increments.getCreatedTemporaries()) {
      if (alreadyCreated.contains(toCreate))
        continue;

      // Keep track of insert position for instruction
      ListIterator<Statement> insertPos = block.statementIterator();

      AliasKey pathToCreate = increments.getAliases().getCanonical(toCreate);

      Deque<Pair<String, Var>> stack = new ArrayDeque<Pair<String,Var>>();
      int pos; // Track which the closest existing parent is
      Var curr = toCreate;
      for (pos = pathToCreate.pathLength() - 1; pos >= 0; pos--) {
        stack.push(Pair.create(pathToCreate.structPath[pos], curr));
        
        AliasKey parentKey = pathToCreate.prefix(pos);
        curr = increments.getAliases().findVar(parentKey);
        assert(curr != null); // Should have been created previously
        if (alreadyCreated.contains(curr) ||
            !increments.getCreatedTemporaries().contains(curr)) {
          // Should already be loaded, don't need to go further
          break;
        }
      }
      assert(pos >= 0); // At least the root should exist      
      
      // Track assigned alias vars in this block so we know where we can
      // insert lookup instructions
      TopDownInfo aliasInfo = parentInfo.makeChild();
      Var parentStruct = curr;
      // Do lookups in dependency order
      while (!stack.isEmpty()) {
        Pair<String, Var> toLookup = stack.pop();
        String field = toLookup.val1;
        Var child = toLookup.val2;
        
        // If curr is alias, may not be able to read yet:
        // must scan down block for location where insert can occur
        if (curr.storage() == Alloc.ALIAS) {
          while (!aliasInfo.initAliasVars.contains(parentStruct)) {
            assert (insertPos.hasNext()) : "Malformed IR, var not init: " +
                    parentStruct;
            Statement stmt = insertPos.next();
            if (stmt.type() == StatementType.INSTRUCTION) {
              aliasInfo.updateForInstruction(stmt.instruction());
            }
          }
        }
        Instruction newInst =
              TurbineOp.structLookup(child, parentStruct, field);
        insertPos.add(newInst);
        aliasInfo.updateForInstruction(newInst);

        alreadyCreated.add(child);
        parentStruct = child;
      }
    }
  }

  /**
   * Try to bring reference increments out from inner blocks. The observation is
   * that increments can safely be done earlier, so if a child wait increments
   * something, then we can just do it here.
   * 
   * We always pull up reference increments if possible in the hope we can
   * cancel them or find a better place put them.  This means that increments
   * will "float" up to the topmost block if possible. We can't really
   * do worse than the explicit increment.
   * 
   * @param block
   * @param increments
   * @param rootBlock if this is the root block we're pulling increments into
   */
  private void pullUpRefIncrements(Block rootBlock, RCTracker increment) {
    for (Statement stmt: rootBlock.getStatements()) {
      if (stmt.type() == StatementType.CONDITIONAL) {
        pullUpRefIncrements(stmt.conditional(), increment);
      } else {
        assert(stmt.type() == StatementType.INSTRUCTION);
        // Do nothing
      }
    }
    
    for (Continuation cont: rootBlock.getContinuations()) {
      pullUpRefIncrements(cont, increment);
    }
   }

  private void pullUpRefIncrements(Continuation cont, RCTracker increment) {
    if (cont.executesBlockOnce()) {
      for (Block inner: cont.getBlocks()) {
        findPullupIncrements(inner, increment, true);
        if (!cont.isAsync() && !cont.runLast()) {
          findPullupDecrements(inner, increment, true);
        }
      }
    } else if (cont.isExhaustiveSyncConditional()) {
      // Find increments that occur on every branch
      pullUpBranches(cont.getBlocks(), !cont.runLast(), increment);
    }
    
  }

  /**
   * Remove positive constant increment instructions from block and update
   * counters.
   * 
   * @param branches blocks that cover execution paths mutually exclusively and
   *                 exhaustively
   * @param runsBeforeCleanups if the continuation executes synchronously before
   *                          cleanups
   * @param increments
   *          counters for a block
   */
  private void pullUpBranches(List<Block> branches, boolean runsBeforeCleanups,
                                    RCTracker increments) {
    RCTracker allBranchIncrements = new RCTracker(increments.getAliases());
    
    // Find intersection of increments before removing anything
    findPullupIncrements(branches.get(0), allBranchIncrements, false);
    if (runsBeforeCleanups) {
      findPullupDecrements(branches.get(0), allBranchIncrements, false);
    }
    
    for (int i = 1; i < branches.size(); i++) {
      Block branch = branches.get(i);
      RCTracker tmpBranchIncrements = new RCTracker(increments.getAliases());
      findPullupIncrements(branch, tmpBranchIncrements, false);
      if (runsBeforeCleanups) {
        findPullupDecrements(branch, tmpBranchIncrements, false);
      }
      
      // Check for intersection
      for (RefCountType rcType: RefcountPass.RC_TYPES) {
        for (RCDir dir: RCDir.values()) {
          Iterator<Entry<AliasKey, Long>> it;
          it = allBranchIncrements.rcIter(rcType, dir).iterator();
          while (it.hasNext()) {
            Entry<AliasKey, Long> e = it.next();
            AliasKey key = e.getKey();
            long amount1 = e.getValue();
            long amount2 = tmpBranchIncrements.getCount(rcType, key, dir);
            // Update to minimum of the two
            if (amount1 == 0 || amount2 == 0) {
              it.remove();
            } else if (dir == RCDir.INCR){
              assert(amount1 > 0);
              assert(amount2 > 0);
              e.setValue(Math.min(amount1, amount2));
            } else {
              assert(dir == RCDir.DECR);
              assert(amount1 < 0);
              assert(amount2 < 0);
              e.setValue(Math.max(amount1, amount2));
            }
          }
        }
      }
    }

    // Remove increments from blocks
    for (Block branch: branches) {
      removePullupIncrements(branch, allBranchIncrements);
      if (runsBeforeCleanups) {
        removePullupDecrements(branch, allBranchIncrements);
      }
    }
    
    // Apply changes to parent increments
    for (RefCountType rcType: RefcountPass.RC_TYPES) {
      for (RCDir dir: RCDir.values()) {
        for (Entry<AliasKey, Long> e: allBranchIncrements.rcIter(rcType, dir)) {
          increments.incrDirect(e.getKey(), rcType, e.getValue());
        }
      }
    }
  }

  private void findPullupIncrements(Block block, RCTracker increments,
                              boolean removeInstructions) {
    ListIterator<Statement> it = block.statementIterator();
    while (it.hasNext()) {
      Statement stmt = it.next();
      switch (stmt.type()) {
        case INSTRUCTION: {
          Instruction inst = stmt.instruction();
          if (RefCountOp.isIncrement(inst.op)) {
            Var v = RefCountOp.getRCTarget(inst);
            Arg amountArg = RefCountOp.getRCAmount(inst);
            RefCountType rcType = RefCountOp.getRCType(inst.op);
            if (amountArg.isIntVal()) {
              long amount = amountArg.getIntLit();
              if (!block.declaredHere(v)) {
                // Check already being manipulated in this block
                // Pull-up by default, if declared outside this block
                increments.incr(v, rcType, amount);
                if (removeInstructions) {
                  it.remove();
                }
              }
            }
          }
          break;
        }
        case CONDITIONAL:
          // Don't try to aggregate these increments here
          break;
        default:
          throw new STCRuntimeError("Unknown statement type " + stmt.type());
      }
    }
  }
  
  private void removePullupIncrements(Block block, RCTracker toRemove) {
    ListIterator<Statement> it = block.statementIterator();
    while (it.hasNext()) {
      Statement stmt = it.next();
      switch (stmt.type()) {
      case INSTRUCTION: {
        Instruction inst = stmt.instruction();
        if (RefCountOp.isIncrement(inst.op)) {
          Var v = RefCountOp.getRCTarget(inst);
          Arg amountArg = RefCountOp.getRCAmount(inst);
          RefCountType rcType = RefCountOp.getRCType(inst.op);
          if (amountArg.isIntVal()) {
            long amount = amountArg.getIntLit();
            long toRemoveAmount = toRemove.getCount(rcType, v, RCDir.INCR);
            assert (toRemoveAmount <= amount && toRemoveAmount >= 0);
            if (toRemoveAmount > 0) {
              it.remove();
              if (toRemoveAmount < amount) {
                // Need to replace with reduced refcount
                Arg newAmount = Arg.createIntLit(amount - toRemoveAmount);
                it.add(RefCountOp.incrRef(rcType, v, newAmount));
              }
            }
          }
        }
        break;
      }
      case CONDITIONAL:
        // Don't try to aggregate these increments here
        break;
      default:
        throw new STCRuntimeError("Unknown statement type " + stmt.type());
      }
    }
  }
  
  
  private void findPullupDecrements(Block block, RCTracker increments,
      boolean removeInstructions) {
    ListIterator<CleanupAction> it = block.cleanupIterator();
    while (it.hasNext()) {
      CleanupAction ca = it.next();
      Instruction inst = ca.action();
      if (RefCountOp.isDecrement(inst.op)) {
        Var v = RefCountOp.getRCTarget(inst);
        Arg amountArg = RefCountOp.getRCAmount(inst);
        RefCountType rcType = RefCountOp.getRCType(inst.op);
        if (amountArg.isIntVal()) {
          long amount = amountArg.getIntLit();
          assert(amount >= 0);
          if (!block.declaredHere(v)) {
            // Check already being manipulated in this block
            // Pull-up by default, if declared outside this block
            increments.incr(v, rcType, -amount);
            if (removeInstructions) {
              it.remove();
            }
          }
        }
      }
    }
  }
  
  private void removePullupDecrements(Block block, RCTracker toRemove) {
    ListIterator<CleanupAction> it = block.cleanupIterator();
    while (it.hasNext()) {
      CleanupAction ca = it.next();
      Instruction inst = ca.action();
      if (RefCountOp.isDecrement(inst.op)) {
        Var v = RefCountOp.getRCTarget(inst);
        Arg amountArg = RefCountOp.getRCAmount(inst);
        RefCountType rcType = RefCountOp.getRCType(inst.op);
        if (amountArg.isIntVal()) {
          long amount = amountArg.getIntLit();
          // Ensure both positive
          long toRemoveAmount = -1 * toRemove.getCount(rcType, v, RCDir.DECR);
          assert (toRemoveAmount >= 0 && toRemoveAmount <= amount);
          if (toRemoveAmount > 0) {
            it.remove();
            if (toRemoveAmount < amount) {
              // Need to replace with reduced refcount
              Arg newAmount = Arg.createIntLit(amount - toRemoveAmount);
              Instruction newDecr = RefCountOp.decrRef(rcType, v, newAmount);
              it.add(new CleanupAction(ca.var(), newDecr));
            }
          }
        }
      }
    }
  }
  
  /**
   * Add decrements that have to happen for continuation inside block
   * 
   * @param cont
   * @param increments
   */
  private void addDecrementsBlocksInsideCont(Continuation cont,
      RCTracker increments) {
    // Get passed in variables decremented inside block
    // Loops don't need this for passed in variables as they decrement refs
    // at loop_break instruction
    if ((cont.spawnsSingleTask() || RCUtil.isAsyncForeachLoop(cont)) &&
        cont.getType() != ContinuationType.LOOP) {
      addDecrementsAsyncCont(cont, increments);
    } else if (cont.getType() == ContinuationType.IF_STATEMENT) {
      Continuation parentCont = cont.parent().getParentCont();
      // This is a slightly hacky way to handle references in a loop.
      // The basic idea is that we want to push down references to either
      // branch of the conditional. In the standard of the loop a conditional
      // will be the last thing in the loop body. We handle this case
      // specifically, as we generally get better results pushing down the
      // decrements to both branches
      if (parentCont != null && parentCont.getType() == ContinuationType.LOOP &&
          getConditionalAtEnd((Loop) parentCont) == cont) {
        addDecrementsLoopStmt((Loop) parentCont, increments);
      }
    } else if (cont.getType() == ContinuationType.LOOP) {
      // Corner case: loop where we can't safely just increment on both branches
      // of
      // if statement. This should be valid in cases opposite to above:
      // where last thing in loop body isn't an if statement.
      Loop loop = (Loop) cont;
      if (getConditionalAtEnd(loop) == null) {
        addDecrementsLoopStmt(loop, increments);
      }
    }
  }

  private void addDecrementsAsyncCont(Continuation cont, RCTracker increments) {
    long amount = 1;

    for (Var keepOpen: cont.getKeepOpenVars()) {
      increments.writeDecr(keepOpen, amount);
    }

    Set<Var> readIncrTmp = new HashSet<Var>();
    for (PassedVar passedIn: cont.getAllPassedVars()) {
      if (!passedIn.writeOnly && RefCounting.hasReadRefCount(passedIn.var)) {
        readIncrTmp.add(passedIn.var);
      }
    }

    // Hold read reference for wait var
    for (BlockingVar blockingVar: cont.blockingVars(false)) {
      if (RefCounting.hasReadRefCount(blockingVar.var)) {
        readIncrTmp.add(blockingVar.var);
      }
    }

    for (Var v: readIncrTmp) {
      increments.readDecr(v, amount);
    }
  }

  /**
   * Add decrements for loop
   * 
   * @param loop
   * @param increments
   */
  private void addDecrementsLoopStmt(Loop loop, RCTracker increments) {
    // Decrement read reference count of all loop iteration vars
    // (which are read-only)
    for (Var v: loop.getLoopVars()) {
      increments.readDecr(v);
    }
  }

  /**
   * Very specific utility function: returns a conditional statement if last
   * thing executed in loop body
   * 
   * @param loop
   * @return
   */
  private Conditional getConditionalAtEnd(Loop loop) {
    Block loopBody = loop.getLoopBody();
    if (loopBody.getStatements().size() != 0) {
      Statement lastStmt = loopBody.statementEndIterator().previous();
      if (lastStmt.type() == StatementType.CONDITIONAL) {
        return lastStmt.conditional();
      }
    }
    return null;
  }

  /**
   * Workaround for Issue #438: just lookup all struct members at top
   * so that at least the fields are in scope.
   * @param logger
   * @param f
   */
  @SuppressWarnings("unchecked")
  private void lookupStructArgMembers(Logger logger, Function f) {
    Block block = f.mainBlock();
    ListIterator<Statement> insertPos = block.statementIterator();
    for (List<Var> args: Arrays.asList(f.getInputList(), f.getOutputList())) {
      lookupAllStructMembers(block, insertPos, args);
    }
  }

  private void lookupAllStructMembers(Block block, List<Var> args) {
    // Start inserting lookups at top
    lookupAllStructMembers(block, block.statementIterator(), args);
  }
  
  private void lookupAllStructMembers(Block block,
      ListIterator<Statement> insertPos, List<Var> args) {
    for (Var arg: args) {
      if (Types.isStruct(arg)) {
        lookupAllStructMembers(block, insertPos, arg);
      }
    }
  }

  private void lookupAllStructMembers(Block block,
          ListIterator<Statement> insertPos, Var arg) {
    assert(Types.isStruct(arg));
    StructType st = (StructType)arg.type().getImplType();
    for (StructField field: st.getFields()) {
      // Fetch field
      String fieldVarName = block.uniqueVarName(
                              Var.structFieldName(arg, field.getName()));
      Var fieldVar = block.declareUnmapped(field.getType(), fieldVarName,
               Alloc.ALIAS, DefType.LOCAL_COMPILER,
               VarProvenance.structField(arg, field.getName()));
      Instruction inst = TurbineOp.structLookup(fieldVar, arg, field.getName());
      if (logger.isTraceEnabled()) {
        logger.trace("Added struct loop for arg " + arg + "." + field.getName()
                     + ": " + inst); 
      }
      insertPos.add(inst);
      inst.setParent(block);
      
      // Recurse on all fields
      if (Types.isStruct(field.getType())) {
        lookupAllStructMembers(block, insertPos, fieldVar);
      }
    }
  }

  /**
   * Place sync continuations (which may use var but not require refcount
   * increment) first to maximize chance of being able to piggyback things.
   */
  private void reorderContinuations(Logger logger, Block block) {
    ListIterator<Continuation> cIt = block.continuationIterator();
    List<Continuation> syncContinuations = new ArrayList<Continuation>();
    while (cIt.hasNext()) {
      Continuation cont = cIt.next();
      if (!cont.isAsync() && !cont.runLast()) {
        cIt.remove();
        syncContinuations.add(cont);
      }
    }
    
    cIt = block.continuationIterator();
    for (Continuation sync: syncContinuations) {
      cIt.add(sync);
    }
  }
}

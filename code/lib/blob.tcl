# Turbine builtin functions for blob manipulation


namespace eval turbine {

  proc blob_size_async { stack out blob } {
    rule "blob_size-$out-$blob" "$blob" \
        $turbine::LOCAL "blob_size_body $out $blob"
  }

  proc blob_size_body { out blob } {
    set blob_val [ retrieve_blob $blob ]
    set sz [ blob_size $blob_val ]
    store_integer $out $sz 
    adlb::blob_free $blob
  }

  proc blob_size { blob_val } {
    return [ lindex $blob_val 1 ]
  }
    
  proc blob_from_string { stack result input } {
    rule "bfs-$input-$result" $input $turbine::LOCAL \
      "blob_from_string_body $input $result"
  }
  proc blob_from_string_body { input result } {
    set t [ retrieve $input ]
    store_blob_string $result $t
  }

  proc string_from_blob { stack result input } {
    rule "sfb-$input-$result" $input $turbine::LOCAL \
      "string_from_blob_body $input $result"
  }
  proc string_from_blob_body { input result } {
    set s [ retrieve_blob_string $input ]
    store_string $result $s
  }

  # Container must be indexed from 0,N-1
  proc blob_from_floats { stack result input } {
    rule "bff-$input-$result" $input $turbine::LOCAL \
      "blob_from_floats_body $input $result"
  }

  proc blob_from_floats_body { container result } {

    set type [ container_typeof $container ]
    set N  [ adlb::container_size $container ]
    c::log "bff_body start"
    set A [ list ]
    for { set i 0 } { $i < $N } { incr i } {
      set td [ container_lookup $container $i ]
      set v  [ retrieve_float $td ]
      lappend A $v
    }

    adlb::store_blob_floats $result $A
    turbine::close_datum $result
  }
}

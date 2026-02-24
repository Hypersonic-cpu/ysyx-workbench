// TODO: fence.i

// FIRST STAGE
.section boot1st, "ax"
.globl _start
.type _start, @function
_start:
  la sp, _stack_pointer
  // Load the 2nd stage loader.
  la t0, _boot2nd_load
  la t1, _boot2nd_beg
  la a5, _boot2nd_end
  bge t1, a5, _boot_1st_end
_boot_1st_loop:
  lw t2, 0(t0)
  sw t2, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  blt t1, a5, _boot_1st_loop
_boot_1st_end:
  la t0, _boot2nd_beg
  fence.i // Make sure the 2nd-stage insts are ready
  jalr 0(t0)

.size _start, . - _start

// SECOND STAGE
.section boot2nd, "ax"
.globl _boot_second
.type _boot_second, @function

_boot_second:
  // Does NOT store ra
  la a0, _text_load
  la a1, _text_beg
  la a2, _data_end
  jal _boot_cpy

  la a0, _data_end
  la a1, _bss_end
  jal _boot_set

  mv s0, zero
  fence.i // Wait until all codes are done
  call _trm_init

_boot_cpy:
  // Move segments. No sp operations
  mv t0, a0
  mv t1, a1
  mv a5, a2
  bge t1, a5, _boot_loop_end
_boot_loop_start:
  lw t2, 0(t0)
  sw t2, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  blt t1, a5, _boot_loop_start
_boot_loop_end:
  ret

_boot_set:
  // Set segments to zero
  mv t1, a0
  mv a5, a1
  bge t1, a5, _boot_set_end
_boot_set_start:
  sw zero, 0(t1)
  addi t1, t1, 4
  blt t1, a5, _boot_set_start
_boot_set_end:
  ret

.size _boot_second, . - _boot_second


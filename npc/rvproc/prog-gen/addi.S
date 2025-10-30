.section .text
.global _start

_start:
  addi t0, x0, 1    
  addi t1, x0, 0x7ff
  addi t2, x0, -1
  addi t3, x0, -0x800
  addi x0, x0, -2   


.section .text
.globl _start

_start:
addi x5, x0, 10          # 00 x5 = 10.
addi x6, x0, 20          # 04 x6 = 20.
addi x2, x0, 0xc         # 08 x2 = 0xc.
jalr x1, x2, 0x14        # 0c jump to 0xc + 0x14 = 0x20
                         #    x2 should be 0x10

addi x7, x0, 99          # 10
addi x8, x0, 100         # 14
addi x0, x0, 0           # 18
addi x0, x0, 0           # 1c

label_func:
addi x5, x5, 20          # 20 x5 = 10 + 20 = 30.
addi x6, x6, 1           # 24 x6 = 20 + 1 = 21.
jalr x0, x1, 0           # 28 ret


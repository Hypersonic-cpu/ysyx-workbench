.text
.org 0x00000000
.globl _start
_start:
    lui  x5, 0x00000      # x5 = 0x00000000
    addi x5, x5, 256      # x5 = 0x00000100 (基地址)
    lui  x6, 0xDEADC      # x6 = 0xDEADC000 (0xDEADB + 1，用于 ADDI 负数偏移)
    addi x6, x6, -273     # -273 是 0xFFFFFEEF。x6 = 0xDEADBEEF
    addi x7, x0, -1       # x7 = 0xFFFFFFFF (所有位为 1)
    addi x8, x0, 127      # x8 = 0x0000007F

    sw   x6, 0(x5)         # 期望 Mem[0x100-0x103] = 0xDEADBEEF (Little Endian: EF BE AD DE)
    sh   x7, 4(x5)         # 期望 Mem[0x104-0x105] = 0xFFFF
    sb   x8, 6(x5)         # 期望 Mem[0x106] = 0x7F

    lw   x9, 0(x5)        # 期望 x9 得到: 0xDEADBEEF
    lh   x10, 4(x5)       # 期望 x10 得到: 0xFFFFFFFF (0xFFFF 符号扩展)
    lbu  x11, 6(x5)       # 期望 x11 得到: 0x0000007F (0x7F 零扩展)


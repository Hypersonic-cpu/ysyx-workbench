.text
.org 0x00000000
.globl _start

_start:
    lui  x5, 0x00000      # LUI 指令加载 0x00000000 到 x5 的高 20 位
    addi x5, x5, 0x30      # ADDI 指令将 256 (0x100) 加到 x5，x5 = 0x00000100
    lw   x6, 0(x5) # 期待得到 (Data[0x100]): 0xDEADBEEF
    lw   x7, 4(x5) # 期待得到 (Data[0x104]): 0x00000001

    lh   x8, 8(x5) # 期待得到: 0xFFFFFFFF (符号扩展后的 -1)
    lh   x9, 10(x5)# 期待得到: 0x00000001

    lb   x10, 12(x5)# 期待得到: 0xFFFFFF80 (符号扩展后的 -128)

    lbu  x11, 12(x5)# 期待得到: 0x00000080 (零扩展后的 128)
    lb   x12, 13(x5)# 期待得到: 0x0000007F (符号扩展后的 127)
    ebreak                 # 退出模拟/调试
    nop
    nop

.data
.org 0x00000030
.word 0xDEADBEEF       # Data[0x100]
.word 0x00000001       # Data[0x104]
.half 0xFFFF           # Data[0x108]
.half 0x0001           # Data[0x10A]
.byte 0x80             # Data[0x10C]
.byte 0x7F             # Data[0x10D]

.section .text
.global _start
_start:
    lui x1, 0x00000  # x1 = 0x00000000
    lui x2, 0xFFFFF  # x2 = 0xFFFFF000
    lui x3, 0x12345  # x3 = 0x12345000
    lui x4, 0x80000  # x4 = 0x80000000
    lui x5, 0x7FFFF  # x5 = 0x7FFFF000
    lui x0, 0x12345  # x0 = 0x00000000
    lui x15, 0x7AAAA # x15 = 0xAAAAA000

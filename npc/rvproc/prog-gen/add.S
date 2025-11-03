addi x1, x0, -1
addi x2, x0, 1
addi x3, x0, 2047
add  x10, x1, x2 // x10 = 0
add  x11, x2, x3 // x11 = 2048
add  x1, x1, x1  // x1 = -2
add  x12, x0, x3
add  x12, x3, x12 // x12 = 4094
ebreak

#ifndef __RISCV_NPC_ADDRMAP_H__
#define __RISCV_NPC_ADDRMAP_H__

#define RV32_NPC_SERIAL  0x10000000
#define RV32_NPC_CLOCK   0x0200bff8
// Default: 1000 MHz. Override at compile time with -DNPC_CYC_PER_US=<value>
#ifndef NPC_CYC_PER_US
#define NPC_CYC_PER_US   1000
#endif

#endif // !__RISCV_NPC_ADDRMAP_H__

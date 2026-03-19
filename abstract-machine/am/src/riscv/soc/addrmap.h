#ifndef __RISCV_SOC_ADDRMAP_H__
#define __RISCV_SOC_ADDRMAP_H__

/** MMIO
 * CLINT         0x0200_0000 ~ 0x0200_ffff
 * SRAM          0x0f00_0000 ~ 0x0fff_ffff
 * UART16550     0x1000_0000 ~ 0x1000_0fff
 * SPI master    0x1000_1000 ~ 0x1000_1fff
 * GPIO          0x1000_2000 ~ 0x1000_200f
 * PS2           0x1001_1000 ~ 0x1001_1007
 * MROM          0x2000_0000 ~ 0x2000_0fff
 * VGA           0x2100_0000 ~ 0x211f_ffff
 * Flash         0x3000_0000 ~ 0x3fff_ffff
 * ChipLink MMIO 0x4000_0000 ~ 0x7fff_ffff
 * PSRAM         0x8000_0000 ~ 0x9fff_ffff
 * SDRAM         0xa000_0000 ~ 0xbfff_ffff
 * ChipLink MEM  0xc000_0000 ~ 0xffff_ffff
 */

#define RV32_SOC_UART_L   0x10000000U
#define RV32_SOC_CLOCK    0x0200bff8U
#define RV32_SOC_MROM_L   0x20000000U
#define RV32_SOC_MROM_H   0x20001000U
#define RV32_SOC_SDRAM_L  0xa0000000U
#define RV32_SOC_SDRAM_H  0xc0000000U
#define RV32_SOC_PS2      0x10011000U
#define RV32_SOC_VGAMEM   0x21000000U

#define UART_OFF_RTX      0U
#define UART_OFF_DIV      0U
#define UART_OFF_THR      0U // Transmit holding reg
#define UART_OFF_LCR      3U // Line control register
#define UART_OFF_LS       5U // Line Status
#define UART_BAUD_RATE    ((uint16_t)115200U)

// SOC_CYC_PER_US is provided through -DSOC_CYC_PER_US=$(MHZ) when building SOC targets.
#ifdef __PLATFORM_SOC__
#ifndef SOC_CYC_PER_US
#error "SOC_CYC_PER_US must be defined for SOC builds (pass MHZ=<frequency> when invoking make)"
#endif
#else
/* Non-SOC builds do not depend on SOC_CYC_PER_US, so the header can still be included. */
#endif

#endif // !__RISCV_NPC_ADDRMAP_H__

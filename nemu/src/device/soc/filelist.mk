SRCS-$(CONFIG_SOC) += src/device/soc/soc.c
SRCS-$(CONFIG_HAS_MROM) += src/device/soc/mrom.c
SRCS-$(CONFIG_HAS_SRAM) += src/device/soc/sram.c
SRCS-$(CONFIG_HAS_FLASH) += src/device/soc/flash.c
SRCS-$(CONFIG_SOC) += src/device/soc/clint.c


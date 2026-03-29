/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include <common.h>
#include <device/map.h>
#include <device/mmio.h>
#include <sys/cdefs.h>

#define SCREEN_W (MUXDEF(CONFIG_VGA_SIZE_800x600, 800, 400))
#define SCREEN_H (MUXDEF(CONFIG_VGA_SIZE_800x600, 600, 300))

static uint32_t screen_width() {
  return MUXDEF(CONFIG_TARGET_AM, io_read(AM_GPU_CONFIG).width, SCREEN_W);
}

static uint32_t screen_height() {
  return MUXDEF(CONFIG_TARGET_AM, io_read(AM_GPU_CONFIG).height, SCREEN_H);
}

static uint32_t screen_size() {
  return screen_width() * screen_height() * sizeof(uint32_t);
}

static void *vmem = NULL;
static uint32_t *vgactl_port_base = NULL;

#ifdef CONFIG_VGA_SHOW_SCREEN
#ifndef CONFIG_TARGET_AM
#include <SDL2/SDL.h>
#include <glob.h>
#include <stdlib.h>
#include <unistd.h>

static SDL_Window *window = NULL;
static SDL_Renderer *renderer = NULL;
static SDL_Texture *texture = NULL;

static void try_init_display_env() {
  if (getenv("DISPLAY") != NULL || getenv("WAYLAND_DISPLAY") != NULL) {
    return;
  }

  char runtime_dir[64];
  snprintf(runtime_dir, sizeof(runtime_dir), "/run/user/%d", getuid());
  if (access(runtime_dir, F_OK) == 0) {
    setenv("XDG_RUNTIME_DIR", runtime_dir, 0);

    if (getenv("XAUTHORITY") == NULL) {
      char pattern[128];
      glob_t matches = {};
      snprintf(pattern, sizeof(pattern), "%s/.mutter-Xwaylandauth.*", runtime_dir);
      if (glob(pattern, 0, NULL, &matches) == 0 && matches.gl_pathc > 0) {
        setenv("XAUTHORITY", matches.gl_pathv[0], 0);
      }
      globfree(&matches);
    }
  }

  if (access("/tmp/.X11-unix/X0", F_OK) == 0) {
    setenv("DISPLAY", ":0", 0);
    setenv("SDL_VIDEODRIVER", "x11", 0);
    return;
  }

  char wayland_sock[96];
  snprintf(wayland_sock, sizeof(wayland_sock), "%s/wayland-0", runtime_dir);
  if (access(wayland_sock, F_OK) == 0) {
    setenv("WAYLAND_DISPLAY", "wayland-0", 0);
  }
}

static void init_screen() {
  char title[128];
  sprintf(title, "%s-NEMU", str(__GUEST_ISA__));
  try_init_display_env();
  Assert(SDL_Init(SDL_INIT_VIDEO) == 0, "SDL_Init failed: %s", SDL_GetError());
  Assert(SDL_CreateWindowAndRenderer(
      SCREEN_W * (MUXDEF(CONFIG_VGA_SIZE_400x300, 2, 1)),
      SCREEN_H * (MUXDEF(CONFIG_VGA_SIZE_400x300, 2, 1)),
      0, &window, &renderer) == 0, "SDL_CreateWindowAndRenderer failed: %s", SDL_GetError());
  Assert(window != NULL && renderer != NULL, "SDL window/renderer not created");
  SDL_SetWindowTitle(window, title);
  SDL_RaiseWindow(window);
  SDL_SetWindowInputFocus(window);
  texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
      SDL_TEXTUREACCESS_STATIC, SCREEN_W, SCREEN_H);
  Assert(texture != NULL, "SDL_CreateTexture failed: %s", SDL_GetError());
  SDL_RenderPresent(renderer);
}

// static inline void
void
__attribute_noinline__
update_screen() {
  SDL_UpdateTexture(texture, NULL, vmem, SCREEN_W * sizeof(uint32_t));
  SDL_RenderClear(renderer);
  SDL_RenderCopy(renderer, texture, NULL, NULL);
  SDL_RenderPresent(renderer);
}
#else
static void init_screen() {}

void
__attribute_noinline__
// static inline void
update_screen() {
  io_write(AM_GPU_FBDRAW, 0, 0, vmem, screen_width(), screen_height(), true);
}
#endif
#endif

static IOMap* vga_ctrl_map = NULL;
void vga_update_screen() {
  // call `update_screen()` when the sync register is non-zero,
  // then zero out the sync register
  bool s = MUXDEF(CONFIG_TARGET_AM, io_read(AM_GPU_FBDRAW).sync,
           map_read(CONFIG_VGA_CTL_MMIO + 4, 4, vga_ctrl_map));
  // printf("DISPLAY = %s\n", s ? "SHOW" : "HIDE");
  if (s) {
    IFDEF(CONFIG_VGA_SHOW_SCREEN, update_screen());
    MUXDEF(CONFIG_TARGET_AM,
           io_write(AM_GPU_FBDRAW, 0, 0, vmem, screen_width(), screen_height(), false),
           map_write(CONFIG_VGA_CTL_MMIO + 4, 4, 0, vga_ctrl_map);
           );
  }
}

void init_vga() {
  vgactl_port_base = (uint32_t *)new_space(8);
  vgactl_port_base[0] = (screen_width() << 16) | screen_height();
#ifdef CONFIG_HAS_PORT_IO
  add_pio_map ("vgactl", CONFIG_VGA_CTL_PORT, vgactl_port_base, 8, NULL);
#else
  vga_ctrl_map = add_mmio_map("vgactl", CONFIG_VGA_CTL_MMIO, vgactl_port_base, 8, NULL);
  assert(vga_ctrl_map);
#endif

  vmem = new_space(screen_size());
  add_mmio_map("vmem", CONFIG_FB_ADDR, vmem, screen_size(), NULL);
  IFDEF(CONFIG_VGA_SHOW_SCREEN, init_screen());
  IFDEF(CONFIG_VGA_SHOW_SCREEN, memset(vmem, 0, screen_size()));
}

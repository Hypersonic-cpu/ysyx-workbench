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
#include <utils.h>
#include <snapshot.h>
#include <device/alarm.h>
#ifndef CONFIG_TARGET_AM
#include <SDL2/SDL.h>
#endif

void init_map();
void init_serial();
void init_timer();
void init_vga();
void init_i8042();
void init_audio();
void init_disk();
void init_sdcard();
void init_alarm();
#ifdef CONFIG_SOC
void init_soc();
#endif

void send_key(uint8_t, bool);
void vga_update_screen();
void timer_snapshot_save(FILE *fp);
bool timer_snapshot_load(FILE *fp);
void keyboard_snapshot_save(FILE *fp);
bool keyboard_snapshot_load(FILE *fp);
void vga_snapshot_save(FILE *fp);
bool vga_snapshot_load(FILE *fp);

static uint64_t device_last_update = 0;

void device_update() {
  uint64_t now = get_time();
  if (now - device_last_update < 1000000 / TIMER_HZ) {
    return;
  }
  device_last_update = now;

  IFDEF(CONFIG_HAS_VGA, vga_update_screen());

#ifndef CONFIG_TARGET_AM
  SDL_Event event;
  while (SDL_PollEvent(&event)) {
    switch (event.type) {
      case SDL_QUIT:
        nemu_state.state = NEMU_QUIT;
        break;
#ifdef CONFIG_HAS_KEYBOARD
      // If a key was pressed
      case SDL_KEYDOWN:
      case SDL_KEYUP: {
        uint8_t k = event.key.keysym.scancode;
        bool is_keydown = (event.key.type == SDL_KEYDOWN);
        send_key(k, is_keydown);
        break;
      }
#endif
      default: break;
    }
  }
#endif
}

void sdl_clear_event_queue() {
#ifndef CONFIG_TARGET_AM
  SDL_Event event;
  while (SDL_PollEvent(&event));
#endif
}

void init_device() {
  IFDEF(CONFIG_TARGET_AM, ioe_init());
  init_map();

  IFDEF(CONFIG_HAS_SERIAL, init_serial());
  IFDEF(CONFIG_HAS_TIMER, init_timer());
  IFDEF(CONFIG_HAS_VGA, init_vga());
  IFDEF(CONFIG_HAS_KEYBOARD, init_i8042());
  IFDEF(CONFIG_HAS_AUDIO, init_audio());
  IFDEF(CONFIG_HAS_DISK, init_disk());
  IFDEF(CONFIG_HAS_SDCARD, init_sdcard());

  IFNDEF(CONFIG_TARGET_AM, init_alarm());

  IFDEF(CONFIG_SOC, init_soc();)
}

void snapshot_device_save(FILE *fp) {
  fwrite(&device_last_update, 1, sizeof(device_last_update), fp);
  timer_snapshot_save(fp);
  keyboard_snapshot_save(fp);
  vga_snapshot_save(fp);
}

bool snapshot_device_load(FILE *fp) {
  bool ok = fread(&device_last_update, 1, sizeof(device_last_update), fp) ==
                sizeof(device_last_update) &&
            timer_snapshot_load(fp) &&
            keyboard_snapshot_load(fp) &&
            vga_snapshot_load(fp);
  return ok;
}

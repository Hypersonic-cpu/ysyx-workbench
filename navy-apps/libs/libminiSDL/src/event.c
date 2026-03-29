#include <NDL.h>
#include <SDL.h>
#include <stdio.h>
#include <string.h>

#define keyname(k) #k,

static const char *keyname[] = {
  "NONE",
  _KEYS(keyname)
};

static uint8_t keystate[sizeof(keyname) / sizeof(keyname[0])] = {};

static int lookup_keycode(const char *name) {
  for (int i = 0; i < (int)(sizeof(keyname) / sizeof(keyname[0])); i++) {
    if (strcmp(name, keyname[i]) == 0) {
      return i;
    }
  }
  return SDLK_NONE;
}

int SDL_PushEvent(SDL_Event *ev) {
  return 0;
}

int SDL_PollEvent(SDL_Event *ev) {
  char buf[64];
  if (!NDL_PollEvent(buf, sizeof(buf))) {
    return 0;
  }
  if (buf[0] != 'k' || (buf[1] != 'd' && buf[1] != 'u') || buf[2] != ' ') {
    return 0;
  }

  char *key = buf + 3;
  key[strcspn(key, " \r\n")] = '\0';

  int code = lookup_keycode(key);
  if (ev) {
    ev->type = (buf[1] == 'd') ? SDL_KEYDOWN : SDL_KEYUP;
    ev->key.type = ev->type;
    ev->key.keysym.sym = code;
  }
  if (code != SDLK_NONE) {
    keystate[code] = (buf[1] == 'd');
    printf("[SDL] key %s %s\n", buf[1] == 'd' ? "down" : "up", key);
  }
  return 1;
}

int SDL_WaitEvent(SDL_Event *event) {
  while (!SDL_PollEvent(event));
  return 1;
}

int SDL_PeepEvents(SDL_Event *ev, int numevents, int action, uint32_t mask) {
  return 0;
}

uint8_t* SDL_GetKeyState(int *numkeys) {
  if (numkeys) {
    *numkeys = sizeof(keystate) / sizeof(keystate[0]);
  }
  return keystate;
}

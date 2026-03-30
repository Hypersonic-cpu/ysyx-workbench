#include <NDL.h>
#include <SDL.h>

int SDL_OpenAudio(SDL_AudioSpec *desired, SDL_AudioSpec *obtained) {
  if (desired) {
    NDL_OpenAudio(desired->freq, desired->channels, desired->samples);
    desired->size = desired->samples * desired->channels * (desired->format / 8);
    if (obtained) {
      *obtained = *desired;
    }
  }
  return 0;
}

void SDL_CloseAudio() {
  NDL_CloseAudio();
}

void SDL_PauseAudio(int pause_on) {
}

void SDL_MixAudio(uint8_t *dst, uint8_t *src, uint32_t len, int volume) {
  for (uint32_t i = 0; i + 1 < len; i += 2) {
    int16_t d = (int16_t)((dst[i + 1] << 8) | dst[i]);
    int16_t s = (int16_t)((src[i + 1] << 8) | src[i]);
    int mixed = d + s * volume / SDL_MIX_MAXVOLUME;
    if (mixed > 32767) mixed = 32767;
    if (mixed < -32768) mixed = -32768;
    dst[i] = mixed & 0xff;
    dst[i + 1] = (mixed >> 8) & 0xff;
  }
}

SDL_AudioSpec *SDL_LoadWAV(const char *file, SDL_AudioSpec *spec, uint8_t **audio_buf, uint32_t *audio_len) {
  return NULL;
}

void SDL_FreeWAV(uint8_t *audio_buf) {
}

void SDL_LockAudio() {
}

void SDL_UnlockAudio() {
}

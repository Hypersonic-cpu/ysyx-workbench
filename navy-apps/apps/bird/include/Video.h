#ifndef _VIDEO_H_
#define _VIDEO_H_

#include <SDL.h>

extern SDL_Surface *gpRenderer;

bool VideoInit();
void VideoDestroy();

#define SCREEN_WIDTH 287
#define SCREEN_HEIGHT 300

#endif

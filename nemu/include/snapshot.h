#ifndef __SNAPSHOT_H__
#define __SNAPSHOT_H__

#include <stdbool.h>
#include <stdio.h>

bool snapshot_save(const char *path);
bool snapshot_load(const char *path);

void snapshot_pmem_save(FILE *fp);
bool snapshot_pmem_load(FILE *fp);

void snapshot_device_save(FILE *fp);
bool snapshot_device_load(FILE *fp);

#endif

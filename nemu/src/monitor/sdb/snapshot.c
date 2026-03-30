#include <isa.h>
#include <snapshot.h>
#include <utils.h>
#include <memory/paddr.h>
#include <stdint.h>
#include <string.h>

typedef struct {
  char magic[8];
  uint32_t version;
  uint32_t reserved;
} SnapshotHeader;

static const SnapshotHeader snapshot_header = {
  .magic = "NEMU-SNP",
  .version = 1,
};

static bool write_all(FILE *fp, const void *buf, size_t size) {
  return fwrite(buf, 1, size, fp) == size;
}

static bool read_all(FILE *fp, void *buf, size_t size) {
  return fread(buf, 1, size, fp) == size;
}

bool snapshot_save(const char *path) {
  FILE *fp = fopen(path, "wb");
  if (fp == NULL) {
    printf("Failed to open snapshot file '%s' for writing\n", path);
    return false;
  }

  bool ok = write_all(fp, &snapshot_header, sizeof(snapshot_header)) &&
            write_all(fp, &nemu_state, sizeof(nemu_state)) &&
            write_all(fp, &cpu, sizeof(cpu));
  if (ok) {
    snapshot_pmem_save(fp);
    snapshot_device_save(fp);
    ok = !ferror(fp);
  }

  fclose(fp);
  if (ok) {
    printf("Snapshot saved to %s\n", path);
  } else {
    printf("Failed to save snapshot to %s\n", path);
  }
  return ok;
}

bool snapshot_load(const char *path) {
  FILE *fp = fopen(path, "rb");
  if (fp == NULL) {
    printf("Failed to open snapshot file '%s' for reading\n", path);
    return false;
  }

  SnapshotHeader hdr;
  bool ok = read_all(fp, &hdr, sizeof(hdr)) &&
            memcmp(&hdr, &snapshot_header, sizeof(hdr)) == 0 &&
            read_all(fp, &nemu_state, sizeof(nemu_state)) &&
            read_all(fp, &cpu, sizeof(cpu)) &&
            snapshot_pmem_load(fp) &&
            snapshot_device_load(fp);

  fclose(fp);
  if (ok) {
    printf("Snapshot loaded from %s\n", path);
  } else {
    printf("Failed to load snapshot from %s\n", path);
  }
  return ok;
}

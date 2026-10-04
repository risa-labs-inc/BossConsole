/*
 * BOSS toolkit preload agent (macOS).
 *
 * Loads JxBrowser's libtoolkit/libipc from Agent_OnLoad, i.e. before the JVM has created its
 * heap, GC workers or JIT compiler threads. Loading libtoolkit swaps the process's default malloc
 * zone (malloc_zone_register(pa), malloc_zone_unregister(default), malloc_zone_register(default)),
 * and a free() on any other thread inside that window executes brk #0 in the Chromium allocator
 * shim. ChromiumToolkitPreload moved the load ahead of AppKit, but the JVM's own threads still
 * raced it: 9.5.37 trapped at libtoolkit+0x4c9f4 with the free on "C2 CompilerThread4". At
 * Agent_OnLoad there are no such threads, so the window has nobody to race.
 *
 * The agent decides nothing. ChromiumToolkitPreload (Java) resolves the engine every launch and
 * writes <boss root>/boss-chromium.preload describing what it loaded and every file the decision
 * depended on. This agent loads those paths on the NEXT launch only when the manifest still
 * describes the disk exactly; on any doubt it loads nothing and the in-JVM preload runs as before.
 * Loading a different copy than the one JxBrowser later loads would put two libtoolkit images in
 * the process (duplicate Objective-C classes), which is worse than the race, hence the strictness.
 *
 * Manifest format, one record per line, fields separated by single spaces, a path always last:
 *   boss-toolkit-preload 1
 *   stamp <token>
 *   guard <size> <inode> <mtime seconds> <mode> <absolute path>
 *   absent <absolute path>
 *   load <absolute path>
 *
 * The outcome is reported through the system property boss.toolkit.preload.agent, which the
 * launcher must declare (empty) so JVMTI can write it: "loaded <n> threads=<t> <path>|<path>" or
 * "skipped <reason>". It never fails JVM startup: Agent_OnLoad always returns JNI_OK.
 */
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <jvmti.h>
#include <mach/mach.h>
#include <pthread.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

#define MANIFEST_NAME "boss-chromium.preload"
#define HEADER "boss-toolkit-preload 1"
#define MAX_LOADS 4
#define MAX_GUARDS 32
#define MAX_LINE 4096
#define MAX_MANIFEST (64 * 1024)
#define RESULT_PROPERTY "boss.toolkit.preload.agent"

static jvmtiEnv *jvmti_env;
static int debug_enabled;
/* Test hook: "manifest=<path>" as the LAST agent option replaces the default location. */
static const char *manifest_override;

static void debug(const char *fmt, const char *arg) {
    if (!debug_enabled) return;
    fprintf(stderr, "[boss-toolkit-preload] ");
    fprintf(stderr, fmt, arg ? arg : "");
    fputc('\n', stderr);
}

/* Matches BossDirectories.isTruthy: "true" (any case), "1", "yes". */
static int is_truthy(const char *v) {
    if (v == NULL) return 0;
    return strcasecmp(v, "true") == 0 || strcmp(v, "1") == 0 || strcasecmp(v, "yes") == 0;
}

/* Matches ChromiumToolkitPreload.disabledFrom: "false", "0", "no", "off" (any case). */
static int is_disabling(const char *v) {
    if (v == NULL) return 0;
    while (*v == ' ' || *v == '\t') v++;
    if (*v == '\0') return 0;
    return strcasecmp(v, "false") == 0 || strcmp(v, "0") == 0 || strcasecmp(v, "no") == 0 ||
           strcasecmp(v, "off") == 0;
}

/* A system property from the command line, or NULL. Caller frees with jvmti Deallocate. */
static char *property(const char *key) {
    char *value = NULL;
    if (jvmti_env == NULL) return NULL;
    if ((*jvmti_env)->GetSystemProperty(jvmti_env, key, &value) != JVMTI_ERROR_NONE) return NULL;
    return value;
}

static void release(char *value) {
    if (value != NULL && jvmti_env != NULL) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)value);
}

static void report(const char *result) {
    debug("%s", result);
    if (jvmti_env == NULL) return;
    /* Fails harmlessly when the launcher did not declare the property. */
    (*jvmti_env)->SetSystemProperty(jvmti_env, RESULT_PROPERTY, result);
}

static int thread_count(void) {
    thread_act_array_t threads;
    mach_msg_type_number_t count = 0;
    if (task_threads(mach_task_self(), &threads, &count) != KERN_SUCCESS) return -1;
    for (mach_msg_type_number_t i = 0; i < count; i++) {
        if (debug_enabled) {
            char name[64] = "";
            pthread_t pt = pthread_from_mach_thread_np(threads[i]);
            if (pt != NULL) pthread_getname_np(pt, name, sizeof name);
            debug("thread: %s", name[0] ? name : (pt == pthread_self() ? "(this thread)" : "(unnamed)"));
        }
        mach_port_deallocate(mach_task_self(), threads[i]);
    }
    vm_deallocate(mach_task_self(), (vm_address_t)threads, count * sizeof(thread_act_t));
    return (int)count;
}

/* Absolute, no "." or ".." segments, no control characters. */
static int is_plain_absolute(const char *p) {
    if (p == NULL || p[0] != '/') return 0;
    for (const char *c = p; *c; c++) {
        if ((unsigned char)*c < 0x20) return 0;
    }
    return strstr(p, "/../") == NULL && strstr(p, "/./") == NULL &&
           !(strlen(p) >= 3 && strcmp(p + strlen(p) - 3, "/..") == 0) &&
           !(strlen(p) >= 2 && strcmp(p + strlen(p) - 2, "/.") == 0);
}

static int ends_with(const char *s, const char *suffix) {
    size_t n = strlen(s), m = strlen(suffix);
    return n >= m && strcmp(s + n - m, suffix) == 0;
}

/* Reads the whole manifest into a NUL-terminated buffer, or returns NULL. */
static char *read_manifest(const char *path) {
    FILE *f = fopen(path, "r");
    if (f == NULL) return NULL;
    char *buf = malloc(MAX_MANIFEST + 1);
    if (buf == NULL) {
        fclose(f);
        return NULL;
    }
    size_t n = fread(buf, 1, MAX_MANIFEST + 1, f);
    fclose(f);
    if (n == 0 || n > MAX_MANIFEST) {
        free(buf);
        return NULL;
    }
    buf[n] = '\0';
    return buf;
}

/*
 * Checks one "guard" record against the disk. Returns NULL when it matches, else a reason.
 * The record is "<size> <inode> <mtime> <mode> <path>".
 */
static const char *check_guard(char *rest, const char **guarded_path) {
    unsigned long long size, inode;
    long long mtime;
    unsigned int mode;
    int consumed = 0;
    if (sscanf(rest, "%llu %llu %lld %o %n", &size, &inode, &mtime, &mode, &consumed) != 4 || consumed == 0) {
        return "malformed guard";
    }
    const char *path = rest + consumed;
    if (!is_plain_absolute(path)) return "guard path not absolute";
    *guarded_path = path;
    struct stat st;
    if (stat(path, &st) != 0) return "guarded file missing";
    if ((unsigned long long)st.st_size != size || (unsigned long long)st.st_ino != inode ||
        (long long)st.st_mtimespec.tv_sec != mtime || (unsigned int)(st.st_mode & 07777) != mode) {
        debug("changed: %s", path);
        return "guarded file changed";
    }
    return NULL;
}

/*
 * Validates the whole manifest before loading anything, so a bad record late in the file cannot
 * leave a half-applied preload. Fills loads[] with pointers into buf. Returns NULL or a reason.
 */
static const char *validate(char *buf, const char *stamp, char **loads, int *load_count) {
    int line_no = 0, saw_stamp = 0, guard_count = 0;
    const char *guards[MAX_GUARDS];
    *load_count = 0;
    char *save = NULL;
    for (char *line = strtok_r(buf, "\n", &save); line != NULL; line = strtok_r(NULL, "\n", &save)) {
        size_t len = strlen(line);
        if (len >= MAX_LINE) return "line too long";
        if (len > 0 && line[len - 1] == '\r') line[len - 1] = '\0';
        if (line_no++ == 0) {
            if (strcmp(line, HEADER) != 0) return "unknown manifest version";
            continue;
        }
        if (strncmp(line, "stamp ", 6) == 0) {
            if (strcmp(line + 6, stamp) != 0) return "stamp differs from this build";
            saw_stamp = 1;
        } else if (strncmp(line, "guard ", 6) == 0) {
            if (guard_count >= MAX_GUARDS) return "too many guards";
            const char *why = check_guard(line + 6, &guards[guard_count]);
            if (why != NULL) return why;
            guard_count++;
        } else if (strncmp(line, "absent ", 7) == 0) {
            const char *path = line + 7;
            struct stat st;
            if (!is_plain_absolute(path)) return "absent path not absolute";
            if (lstat(path, &st) == 0 || errno != ENOENT) return "a path that must be absent exists";
        } else if (strncmp(line, "load ", 5) == 0) {
            char *path = line + 5;
            if (!is_plain_absolute(path) || !ends_with(path, ".dylib")) return "load path rejected";
            if (*load_count >= MAX_LOADS) return "too many loads";
            loads[(*load_count)++] = path;
        } else if (line[0] != '\0') {
            return "unknown record";
        }
    }
    if (line_no == 0) return "empty manifest";
    if (!saw_stamp) return "no stamp";
    if (*load_count == 0) return "nothing to load";
    /* Every loaded file must also be guarded, or a replaced library would load unchecked. */
    for (int i = 0; i < *load_count; i++) {
        int guarded = 0;
        for (int g = 0; g < guard_count && !guarded; g++) guarded = strcmp(guards[g], loads[i]) == 0;
        if (!guarded) return "load target not guarded";
        struct stat st;
        if (stat(loads[i], &st) != 0 || !S_ISREG(st.st_mode)) return "load target missing";
    }
    return NULL;
}

static void run(void) {
    char *disabled_prop = property("boss.toolkit.preload");
    int disabled = is_disabling(getenv("BOSS_TOOLKIT_PRELOAD"));
    /* Env wins over the property, matching the Java side; a blank env does not shadow it. */
    const char *env = getenv("BOSS_TOOLKIT_PRELOAD");
    if (env == NULL || env[strspn(env, " \t")] == '\0') disabled = is_disabling(disabled_prop);
    release(disabled_prop);
    if (disabled) {
        report("skipped disabled");
        return;
    }

    char *stamp = property("boss.toolkit.preload.stamp");
    if (stamp == NULL || stamp[0] == '\0') {
        release(stamp);
        report("skipped no stamp");
        return;
    }

    char *dev_prop = property("boss.dev.mode");
    int dev = is_truthy(dev_prop) || is_truthy(getenv("BOSS_DEV_MODE"));
    release(dev_prop);

    /* Java's user.home on macOS is the passwd entry, not $HOME. */
    struct passwd *pw = getpwuid(getuid());
    if (pw == NULL || pw->pw_dir == NULL || pw->pw_dir[0] != '/') {
        release(stamp);
        report("skipped no home directory");
        return;
    }
    char manifest_path[PATH_MAX];
    int written = manifest_override != NULL
                      ? snprintf(manifest_path, sizeof manifest_path, "%s", manifest_override)
                      : snprintf(manifest_path, sizeof manifest_path, "%s/%s/%s", pw->pw_dir,
                                 dev ? ".boss_debug" : ".boss", MANIFEST_NAME);
    if (written < 0 || written >= (int)sizeof manifest_path) {
        release(stamp);
        report("skipped path too long");
        return;
    }

    char *buf = read_manifest(manifest_path);
    if (buf == NULL) {
        release(stamp);
        report("skipped no manifest");
        return;
    }
    char *loads[MAX_LOADS];
    int load_count = 0;
    const char *why = validate(buf, stamp, loads, &load_count);
    release(stamp);
    if (why != NULL) {
        char result[256];
        snprintf(result, sizeof result, "skipped %s", why);
        report(result);
        free(buf);
        return;
    }

    int threads = thread_count();
    char result[PATH_MAX * MAX_LOADS + 64];
    int loaded = 0;
    size_t used = 0;
    result[0] = '\0';
    char paths[PATH_MAX * MAX_LOADS];
    paths[0] = '\0';
    for (int i = 0; i < load_count; i++) {
        /* Same mode HotSpot's os::dll_load uses, so its later System.load finds this image. */
        if (dlopen(loads[i], RTLD_LAZY) == NULL) {
            debug("dlopen failed: %s", dlerror());
            break;
        }
        int n = snprintf(paths + used, sizeof paths - used, "%s%s", loaded ? "|" : "", loads[i]);
        if (n > 0 && (size_t)n < sizeof paths - used) used += (size_t)n;
        loaded++;
    }
    if (loaded == 0) {
        report("skipped dlopen failed");
    } else {
        snprintf(result, sizeof result, "loaded %d threads=%d %s", loaded, threads, paths);
        report(result);
    }
    free(buf);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    if (options != NULL) {
        const char *m = strstr(options, "manifest=");
        manifest_override = m != NULL ? m + strlen("manifest=") : NULL;
        /* Only look for "debug" before the manifest path, which may itself contain the word. */
        size_t head = m != NULL ? (size_t)(m - options) : strlen(options);
        for (size_t i = 0; i + 5 <= head; i++) {
            if (strncmp(options + i, "debug", 5) == 0) debug_enabled = 1;
        }
    }
    if ((*vm)->GetEnv(vm, (void **)&jvmti_env, JVMTI_VERSION_1_2) != JNI_OK) jvmti_env = NULL;
    run();
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) { (void)vm; }

// Credits limit unshown presents. A lease identifies its credit even if a late callback arrives after recovery.
#pragma once
#include <pthread.h>
#include <stdint.h>
#include <time.h>
#include <errno.h>

typedef struct { uint64_t ticket, stale[2]; } PMPresentLease;
typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t available;
    uint64_t held[2], next;
} PMPresentCredits;

static inline void pm_credits_init(PMPresentCredits *c) {
    pthread_mutex_init(&c->lock, NULL);
    pthread_cond_init(&c->available, NULL);
    c->held[0] = c->held[1] = c->next = 0;
}

// timeoutNs=0 never blocks. If no credit is available, record exactly which leases were held at this point.
static inline PMPresentLease pm_credits_acquire(PMPresentCredits *c, long timeoutNs) {
    PMPresentLease result = {0};
    struct timespec deadline;
    if (timeoutNs) {
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_nsec += timeoutNs;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    }
    pthread_mutex_lock(&c->lock);
    while (timeoutNs && c->held[0] && c->held[1]) {
        int rc = pthread_cond_timedwait(&c->available, &c->lock, &deadline);
        if (rc != 0) break;
    }
    for (int i = 0; i < 2; i++) {
        if (!c->held[i]) {
            if (++c->next == 0) ++c->next;
            result.ticket = c->held[i] = c->next;
            break;
        }
    }
    if (!result.ticket) { result.stale[0] = c->held[0]; result.stale[1] = c->held[1]; }
    pthread_mutex_unlock(&c->lock);
    return result;
}

// A forced present can recover only the leases it observed, and only once it is actually shown. Late callbacks
// cannot release leases issued later. This touches pacing credits only, never GPU resources or slot ownership.
static inline void pm_credits_finish(PMPresentCredits *c, PMPresentLease lease, int shown) {
    pthread_mutex_lock(&c->lock);
    int released = 0;
    for (int i = 0; i < 2; i++) {
        if (c->held[i] && (lease.ticket == c->held[i] ||
                (!lease.ticket && shown && lease.stale[i] == c->held[i]))) {
            c->held[i] = 0;
            released = 1;
        }
    }
    if (released) pthread_cond_broadcast(&c->available);
    pthread_mutex_unlock(&c->lock);
}

static inline void pm_credits_destroy(PMPresentCredits *c) {
    pthread_cond_destroy(&c->available);
    pthread_mutex_destroy(&c->lock);
}

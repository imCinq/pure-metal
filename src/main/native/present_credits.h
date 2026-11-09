// Credits limit unshown presents. A lease identifies its credit even if a late callback arrives after recovery.
#pragma once
#include <pthread.h>
#include <stdint.h>
#include <time.h>
#include <errno.h>
#include <IOSurface/IOSurface.h>

typedef struct { uint64_t ticket, stale[2]; } PMPresentLease;
typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t available;
    uint64_t held[2], next;
    uint64_t imageTicket[2];
    IOSurfaceRef image[2];
} PMPresentCredits;

static inline void pm_credits_init(PMPresentCredits *c) {
    pthread_mutex_init(&c->lock, NULL);
    pthread_cond_init(&c->available, NULL);
    c->held[0] = c->held[1] = c->next = 0;
    c->imageTicket[0] = c->imageTicket[1] = 0;
    c->image[0] = c->image[1] = NULL;
}

// The layer has returned this image for another use. Retain the earlier image while its lease is
// held so its ID cannot be recycled. A late callback is still restricted to its original ticket.
static inline int pm_credits_bind_image(PMPresentCredits *c, PMPresentLease lease, IOSurfaceRef image) {
    if (!image) return 0;
    pthread_mutex_lock(&c->lock);
    int recovered = 0;
    IOSurfaceID id = IOSurfaceGetID(image);
    for (int i = 0; i < 2; i++) {
        if (c->image[i] && c->imageTicket[i] == c->held[i] && c->held[i] &&
            c->held[i] != lease.ticket && IOSurfaceGetID(c->image[i]) == id) {
            c->held[i] = 0;
            c->imageTicket[i] = 0;
            CFRelease(c->image[i]); c->image[i] = NULL;
            recovered++;
        }
    }
    for (int i = 0; i < 2; i++) {
        if (lease.ticket && c->held[i] == lease.ticket) {
            if (c->image[i]) CFRelease(c->image[i]);
            c->image[i] = (IOSurfaceRef)CFRetain(image);
            c->imageTicket[i] = lease.ticket;
        }
    }
    if (recovered) pthread_cond_broadcast(&c->available);
    pthread_mutex_unlock(&c->lock);
    return recovered;
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
            if (c->image[i]) { CFRelease(c->image[i]); c->image[i] = NULL; }
            c->imageTicket[i] = 0;
            released = 1;
        }
    }
    if (released) pthread_cond_broadcast(&c->available);
    pthread_mutex_unlock(&c->lock);
}

static inline void pm_credits_destroy(PMPresentCredits *c) {
    for (int i = 0; i < 2; i++) if (c->image[i]) CFRelease(c->image[i]);
    pthread_cond_destroy(&c->available);
    pthread_mutex_destroy(&c->lock);
}

#include "../src/main/native/present_credits.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    PMPresentCredits c;
    pm_credits_init(&c);
    PMPresentLease a = pm_credits_acquire(&c, 0), b = pm_credits_acquire(&c, 0);
    assert(a.ticket && b.ticket && a.ticket != b.ticket);
    PMPresentLease forced = pm_credits_acquire(&c, 0);
    assert(!forced.ticket && forced.stale[0] && forced.stale[1]);
    pm_credits_finish(&c, forced, 0); // failure or no presentation must not release anything
    assert(!pm_credits_acquire(&c, 0).ticket);
    pm_credits_finish(&c, forced, 1); // forced present is actually shown
    PMPresentLease x = pm_credits_acquire(&c, 0), y = pm_credits_acquire(&c, 0);
    assert(x.ticket && y.ticket && x.ticket != y.ticket);
    pm_credits_finish(&c, a, 1); pm_credits_finish(&c, b, 1); // late callbacks must not free new credits
    pm_credits_finish(&c, forced, 1); // repeated callback must not free new credits
    assert(!pm_credits_acquire(&c, 0).ticket);
    PMPresentLease stale = pm_credits_acquire(&c, 0);
    pm_credits_finish(&c, x, 1);
    PMPresentLease z = pm_credits_acquire(&c, 0); // credit reused before fallback callback
    assert(z.ticket);
    pm_credits_finish(&c, stale, 1); // only still-matching y is reclaimed
    PMPresentLease next = pm_credits_acquire(&c, 0);
    assert(next.ticket && !pm_credits_acquire(&c, 0).ticket);
    pm_credits_finish(&c, y, 1); // stale y must not release next
    assert(!pm_credits_acquire(&c, 0).ticket);
    pm_credits_finish(&c, z, 1); pm_credits_finish(&c, next, 1);
    assert(pm_credits_acquire(&c, 100000000L).ticket);
    pm_credits_destroy(&c);
    puts("PASS presentation credit recovery, stale callbacks, and reused leases");
}

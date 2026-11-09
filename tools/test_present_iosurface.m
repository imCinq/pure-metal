// clang -fobjc-arc -O1 -g -fsanitize=address -Wall -Wextra -Werror -framework Foundation -framework IOSurface tools/test_present_iosurface.m -o /tmp/pm-iosurface-test && /tmp/pm-iosurface-test
#import <Foundation/Foundation.h>
#import "../src/main/native/present_credits.h"
#include <assert.h>
#include <stdio.h>
static IOSurfaceRef new_image(void) {
    NSDictionary *props = @{(__bridge NSString *)kIOSurfaceWidth: @16, (__bridge NSString *)kIOSurfaceHeight: @16, (__bridge NSString *)kIOSurfaceBytesPerElement: @4};
    return IOSurfaceCreate((__bridge CFDictionaryRef)props);
}
int main(void) { @autoreleasepool {
    IOSurfaceRef a=new_image(), b=new_image(), c=new_image(); assert(a && b && c);
    PMPresentCredits pc; pm_credits_init(&pc);
    PMPresentLease lost=pm_credits_acquire(&pc,0); assert(lost.ticket);
    assert(!pm_credits_bind_image(&pc,lost,a));
    PMPresentLease active=pm_credits_acquire(&pc,0); assert(active.ticket);
    assert(!pm_credits_bind_image(&pc,active,b));
    pm_credits_finish(&pc,active,1);
    PMPresentLease next=pm_credits_acquire(&pc,0); assert(next.ticket);
    assert(!pm_credits_bind_image(&pc,next,c));
    assert(!pm_credits_acquire(&pc,0).ticket);
    pm_credits_finish(&pc,next,1);
    PMPresentLease reused=pm_credits_acquire(&pc,0); assert(reused.ticket);
    assert(pm_credits_bind_image(&pc,reused,a)==1);
    PMPresentLease fresh=pm_credits_acquire(&pc,0); assert(fresh.ticket);
    pm_credits_finish(&pc,lost,1); assert(!pm_credits_acquire(&pc,0).ticket);
    assert(!pm_credits_bind_image(&pc,fresh,NULL));
    pm_credits_finish(&pc,reused,1); pm_credits_finish(&pc,fresh,1);
    PMPresentLease old=pm_credits_acquire(&pc,0); assert(old.ticket); assert(!pm_credits_bind_image(&pc,old,a));
    pm_credits_finish(&pc,old,1);
    PMPresentLease current=pm_credits_acquire(&pc,0); assert(current.ticket); assert(!pm_credits_bind_image(&pc,current,b));
    pm_credits_finish(&pc,old,1);
    PMPresentLease other=pm_credits_acquire(&pc,0); assert(other.ticket);
    assert(!pm_credits_bind_image(&pc,other,a)); assert(!pm_credits_acquire(&pc,0).ticket);
    pm_credits_finish(&pc,current,1); pm_credits_finish(&pc,other,1);
    pm_credits_destroy(&pc); CFRelease(a); CFRelease(b); CFRelease(c);
    puts("PASS same-image recovery, distinct-image rejection, missing image and late callback generation safety");
}}

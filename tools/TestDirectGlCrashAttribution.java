package io.github.imcinq.puremetal.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Headless regression tests for {@link DirectGlCrashAttribution}. */
public final class TestDirectGlCrashAttribution {
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(final String[] args) throws Exception {
        Path temp = Files.createTempDirectory("pm-gl-crash-test-");
        try {
            long now = System.currentTimeMillis();
            DirectGlCrashAttribution.ModResolver resolver = (cls, method) -> {
                String mixinMod = DirectGlCrashAttribution.extractMixinModId(method);
                if ("coolshaders".equals(mixinMod)) {
                    return new DirectGlCrashAttribution.ModRef("coolshaders", "Cool Shaders");
                }
                if ("gl-hook".equals(mixinMod)) {
                    return new DirectGlCrashAttribution.ModRef("gl-hook", "GL Hook\n\u2028/opt/secret/foo");
                }
                if ("sep-mod".equals(mixinMod)) {
                    return new DirectGlCrashAttribution.ModRef("sep-mod", "Sep\u2028Line\u2029Para\u0085Nel");
                }
                String norm = DirectGlCrashAttribution.normalizeClassName(cls);
                if (norm.startsWith("com.example.glmod.")) {
                    return new DirectGlCrashAttribution.ModRef("glmod", "GL Mod");
                }
                if (norm.startsWith("org.fancy.sky.")) {
                    return new DirectGlCrashAttribution.ModRef("fancysky", "Fancy Sky");
                }
                return null;
            };

            // 1. Direct LWJGL null-pointer crash with real libpuremetal-<hash>.dylib in Dynamic libraries.
            Path dir1 = Files.createDirectory(temp.resolve("case1"));
            Path marker1 = dir1.resolve("marker.txt");
            Path err1 = dir1.resolve("hs_err_pid4242.log");
            Files.writeString(err1, """
                # A fatal error has been detected by the Java Runtime Environment:
                #  SIGSEGV (0xb) at pc=0x0000000000000000, pid=4242, tid=259
                # Problematic frame:
                # C  0x0000000000000000
                Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
                C  0x0000000000000000

                Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
                j  org.lwjgl.system.JNI.callPV(IJ)V+0
                j  org.lwjgl.opengl.GL11C.glBindTexture(II)V+12
                j  com.example.glmod.client.CustomRenderer$$Lambda/0x0000000100123456.run()V+4
                j  net.minecraft.client.gui.Gui.render()V+10

                Dynamic libraries:
                0x0000000120000000 \t/tmp/game/.puremetal/libpuremetal-abcdef1234.dylib
                """);
            var a1 = DirectGlCrashAttribution.checkDirectories(List.of(dir1), marker1, -1L, now, resolver);
            require(a1 != null && !a1.confirmedMetal(), "case 1 should attribute dylib-only GL crash");
            require("glmod".equals(a1.modId()), "case 1 lambda normalization modId: " + a1.modId());
            require("org.lwjgl.opengl.GL11C.glBindTexture".equals(a1.glCall()), "case 1 glCall: " + a1.glCall());
            require(a1.summary().contains("GL Mod (glmod)") && a1.summary().contains("hs_err_pid4242.log"), "case 1 summary: " + a1.summary());
            require(a1.summary().contains("If Metal was the active backend") && !a1.summary().contains("remove or disable"),
                "case 1 dylib-only wording must stay factual: " + a1.summary());
            require(!a1.summary().contains(dir1.toString()), "case 1 summary must not leak directory path");
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir1), marker1, -1L, now, resolver) == null, "case 1 must not report twice");

            // 2. Mention-only libpuremetal in VM Arguments (no Dynamic libraries entry) must NOT blame a mod!
            Path dir2 = Files.createDirectory(temp.resolve("case2"));
            Path err2 = dir2.resolve("hs_err_pid4300.log");
            Files.writeString(err2, """
                # Problematic frame:
                # C  [liblwjgl_opengl.dylib+0x1234]  Java_org_lwjgl_opengl_GL11C_nglGetString+0x18
                Java frames:
                j  org.lwjgl.opengl.GL11C.nglGetString(I)J+0
                j  com.example.glmod.client.CustomRenderer.drawHud()V+45

                Dynamic libraries:
                0x0000000100000000 \t/usr/lib/libSystem.B.dylib

                VM Arguments:
                jvm_args: -Djava.library.path=/tmp/libpuremetal.dylib -Dpuremetal.foo=io.github.imcinq.puremetal
                """);
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir2), dir2.resolve("m.txt"), -1L, now, resolver) == null,
                "case 2 mention-only libpuremetal in VM args must not qualify");

            // 3. Hyphenated Fabric mod ID in MixinExtras handler on GlStateManager + path/Unicode separator sanitization + jvmci frame.
            Path dir3 = Files.createDirectory(temp.resolve("case3"));
            Path err3 = dir3.resolve("hs_err_pid5151.log");
            Files.writeString(err3, """
                #  SIGSEGV (0xb) at pc=0x0, pid=5151, tid=259
                Native frames:
                C  [liblwjgl_opengl.dylib+0x1234]  Java_org_lwjgl_opengl_GL11C_nglGetString+0x18

                Java frames:
                J 812 jvmci org.lwjgl.opengl.GL11C.glGetString(I)Ljava/lang/String; (12 bytes) @ 0x000000011234
                j  com.mojang.blaze3d.opengl.GlStateManager.mixinextras$bridge$abc123$gl-hook$getStr(I)Ljava/lang/String;+1
                """);
            var a3 = DirectGlCrashAttribution.checkDirectories(List.of(dir3), dir3.resolve("m.txt"), 5151L, now, resolver);
            require(a3 != null && a3.confirmedMetal() && "gl-hook".equals(a3.modId()), "case 3 should detect hyphenated MixinExtras handler on GlStateManager: " + a3);
            require(!a3.summary().contains("\n") && !a3.summary().contains("\u2028") && !a3.summary().contains("/opt/secret"),
                "case 3 must drop local-path mod name and Unicode separators: " + a3.summary());

            Path dir3b = Files.createDirectory(temp.resolve("case3b"));
            Path err3b = dir3b.resolve("hs_err_pid5152.log");
            Files.writeString(err3b, """
                #  SIGSEGV (0xb) at pc=0x0, pid=5152, tid=259
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  net.minecraft.client.gui.Gui.handler$zba000$sep-mod$render()V+1
                """);
            var a3b = DirectGlCrashAttribution.checkDirectories(List.of(dir3b), dir3b.resolve("m.txt"), 5152L, now, resolver);
            require(a3b != null && "sep-mod".equals(a3b.modId()), "case 3b hyphenated handler: " + a3b);
            require(!a3b.summary().contains("\u2028") && !a3b.summary().contains("\u2029") && !a3b.summary().contains("\u0085"),
                "case 3b must flatten Unicode line/paragraph separators: " + a3b.summary());

            // 4. Incidental GL frame below an unrelated native crash must NOT be blamed!
            Path dir4 = Files.createDirectory(temp.resolve("case4"));
            Path err4 = dir4.resolve("hs_err_pid6000.log");
            Files.writeString(err4, """
                # Problematic frame:
                # C  [libaudio_broken.dylib+0x42]  broken_mix+0x10
                Native frames:
                C  [libaudio_broken.dylib+0x42]  broken_mix+0x10
                j  com.audio.BrokenAudio.mix()V+12
                j  org.lwjgl.opengl.GL11C.glFinish()V+4
                j  com.example.glmod.client.CustomRenderer.drawHud()V+45

                Dynamic libraries:
                0x0000000120000000 \t/tmp/libpuremetal.dylib
                """);
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir4), dir4.resolve("m.txt"), -1L, now, resolver) == null,
                "case 4 incidental GL frame below unrelated fault must be ignored");

            // 5. Native-only / header-only report (truncated before Java frames).
            Path dir5 = Files.createDirectory(temp.resolve("case5"));
            Path err5 = dir5.resolve("hs_err_pid6500.log");
            Files.writeString(err5, """
                #  SIGSEGV (0xb) at pc=0x1, pid=6500, tid=259
                # Problematic frame:
                # C  [GLEngine+0x9999]  glDrawElements_ACC_Exec+0x20

                Dynamic libraries:
                0x0000000120000000 \t/tmp/libpuremetal.dylib
                """);
            var a5 = DirectGlCrashAttribution.checkDirectories(List.of(dir5), dir5.resolve("m.txt"), -1L, now, resolver);
            require(a5 != null && "native:GLEngine".equals(a5.glCall()) && a5.callerClass() == null, "case 5 header-only GLEngine report: " + a5);

            // 6. Cross-directory selection: exact PID in CWD beats newer PID-less report in gameDir, and no ping-pong dedup!
            Path gameDir = Files.createDirectory(temp.resolve("case6_game"));
            Path cwdDir = Files.createDirectory(temp.resolve("case6_cwd"));
            Path marker6 = gameDir.resolve("marker6.txt");
            Path gameErr = gameDir.resolve("hs_err_pid7001.log");
            Path cwdErr = cwdDir.resolve("hs_err_pid7002.log");
            Files.writeString(gameErr, """
                #  SIGSEGV (0xb) at pc=0x0, pid=7001, tid=259
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  org.fancy.sky.SkyRenderer.setup()V+4
                Dynamic libraries:
                0x0000000120000000 \t/tmp/libpuremetal.dylib
                """);
            Files.writeString(cwdErr, """
                #  SIGSEGV (0xb) at pc=0x0, pid=7002, tid=259
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  com.example.glmod.client.CustomRenderer.drawHud()V+4
                Dynamic libraries:
                0x0000000120000000 \t/tmp/libpuremetal.dylib
                """);
            Files.setLastModifiedTime(cwdErr, FileTime.fromMillis(now - 5000L));
            Files.setLastModifiedTime(gameErr, FileTime.fromMillis(now - 1000L));
            // Exact PID 7002 in cwdDir wins over newer 7001 in gameDir.
            var a6a = DirectGlCrashAttribution.checkDirectories(List.of(gameDir, cwdDir), marker6, 7002L, now, resolver);
            require(a6a != null && "glmod".equals(a6a.modId()), "case 6a exact PID in CWD should win: " + a6a);
            // Without PID, single newest across both (gameErr) is chosen once, then deduplicated forever (never falls back to cwdErr!).
            Path marker6b = gameDir.resolve("marker6b.txt");
            var a6b = DirectGlCrashAttribution.checkDirectories(List.of(gameDir, cwdDir), marker6b, -1L, now, resolver);
            require(a6b != null && "fancysky".equals(a6b.modId()), "case 6b newest across both dirs: " + a6b);
            require(DirectGlCrashAttribution.checkDirectories(List.of(gameDir, cwdDir), marker6b, -1L, now, resolver) == null,
                "case 6b must not alternate to older report in second dir");

            // 7. Malformed UTF-8 bytes and giant line do not break parsing or blow memory.
            Path dir7 = Files.createDirectory(temp.resolve("case7"));
            Path err7 = dir7.resolve("hs_err_pid8888.log");
            byte[] badPrefix = new byte[] { '#', ' ', (byte) 0xFF, (byte) 0xFE, '\n' };
            String giant = "# " + "X".repeat(20000) + "\n"
                + "Java frames:\n"
                + "j  org.lwjgl.opengl.GL20C.glUseProgram(I)V+0\n"
                + "j  net.minecraft.client.renderer.LevelRenderer.modify$abc123$coolshaders$sky()V+4\n\n"
                + "Dynamic libraries:\n"
                + "0x0000000120000000 \t/tmp/libpuremetal.dylib\n";
            byte[] giantBytes = giant.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[badPrefix.length + giantBytes.length];
            System.arraycopy(badPrefix, 0, combined, 0, badPrefix.length);
            System.arraycopy(giantBytes, 0, combined, badPrefix.length, giantBytes.length);
            Files.write(err7, combined);
            var a7 = DirectGlCrashAttribution.checkDirectories(List.of(dir7), dir7.resolve("m.txt"), -1L, now, resolver);
            require(a7 != null && "coolshaders".equals(a7.modId()), "case 7 malformed UTF-8 + giant line + modify prefix: " + a7);

            // 8. Non-GL Pure Metal crash -> must return null.
            Path dir8 = Files.createDirectory(temp.resolve("case8"));
            Path err8 = dir8.resolve("hs_err_pid9000.log");
            Files.writeString(err8, """
                #  SIGSEGV (0xb) at pc=0x0, pid=9000, tid=259
                Native frames:
                C  [libpuremetal.dylib+0x9999]  pm_submit+0x10
                Java frames:
                j  io.github.imcinq.puremetal.backend.MetalCommandEncoder.submit()V+8
                """);
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir8), dir8.resolve("m.txt"), 9000L, now, resolver) == null,
                "case 8 must ignore non-GL crash");

            // 9. Vanilla OpenGL crash (GlStateManager boundary) with libpuremetal loaded must NOT blame an unrelated mod.
            Path dir9 = Files.createDirectory(temp.resolve("case9"));
            Path err9 = dir9.resolve("hs_err_pid9101.log");
            Files.writeString(err9, """
                #  SIGSEGV (0xb) at pc=0x0, pid=9101, tid=259
                # Problematic frame:
                # C  [GLEngine+0x1234]  glDrawElements_ACC_Exec+0x10
                Java frames:
                j  org.lwjgl.opengl.GL11C.glDrawElements(IIIJ)V+0
                j  com.mojang.blaze3d.opengl.GlStateManager._drawElements(IIIJ)V+4
                j  com.example.glmod.client.CustomRenderer.drawHud()V+45

                Dynamic libraries:
                0x0000000120000000 \t/tmp/libpuremetal.dylib
                """);
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir9), dir9.resolve("m9a.txt"), -1L, now, resolver) == null,
                "case 9a dylib-only vanilla GL boundary crash must not emit warning or blame mod");
            var a9b = DirectGlCrashAttribution.checkDirectories(List.of(dir9), dir9.resolve("m9b.txt"), 9101L, now, resolver);
            require(a9b != null && a9b.confirmedMetal() && a9b.modId() == null
                && "com.mojang.blaze3d.opengl.GlStateManager".equals(a9b.callerClass()),
                "case 9b confirmed-Metal vanilla GL boundary must stop at GlStateManager and never blame deeper mod: " + a9b);
            require(!a9b.summary().contains("remove the mod"),
                "case 9b vanilla caller summary must not tell user to remove a mod: " + a9b.summary());

            // 10. Exact-PID verification: missing header PID must not qualify without dylib; newer mismatched header must not block valid match in other dir.
            Path dir10a = Files.createDirectory(temp.resolve("case10_a"));
            Path dir10b = Files.createDirectory(temp.resolve("case10_b"));
            Path err10Missing = dir10a.resolve("hs_err_pid7770.log");
            Files.writeString(err10Missing, """
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  com.example.glmod.client.CustomRenderer.drawHud()V+4
                """);
            require(DirectGlCrashAttribution.checkDirectories(List.of(dir10a), dir10a.resolve("m10a.txt"), 7770L, now, resolver) == null,
                "case 10a missing header PID without dylib must not qualify as exact PID");

            Path err10Bad = dir10a.resolve("hs_err_pid7777.log");
            Path err10Good = dir10b.resolve("hs_err_pid7777.log");
            Files.writeString(err10Bad, """
                #  SIGSEGV (0xb) at pc=0x0, pid=9999, tid=259
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  org.fancy.sky.SkyRenderer.setup()V+4
                """);
            Files.writeString(err10Good, """
                #  SIGSEGV (0xb) at pc=0x0, pid=7777, tid=259
                Java frames:
                j  org.lwjgl.opengl.GL11C.glFinish()V+0
                j  com.example.glmod.client.CustomRenderer.drawHud()V+4
                """);
            Files.setLastModifiedTime(err10Good, FileTime.fromMillis(now - 5000L));
            Files.setLastModifiedTime(err10Bad, FileTime.fromMillis(now - 1000L));
            var a10 = DirectGlCrashAttribution.checkDirectories(List.of(dir10a, dir10b), dir10a.resolve("m10b.txt"), 7777L, now, resolver);
            require(a10 != null && a10.confirmedMetal() && "glmod".equals(a10.modId()),
                "case 10b verified exact PID in dir10b must beat newer mismatched header in dir10a: " + a10);

            // 11. Neutral Java problematic frame (org.lwjgl.system.JNI) keeps topFaultChain alive, and puremetal mixin frame is skipped.
            Path dir11 = Files.createDirectory(temp.resolve("case11"));
            Path err11 = dir11.resolve("hs_err_pid9200.log");
            Files.writeString(err11, """
                #  SIGSEGV (0xb) at pc=0x0, pid=9200, tid=259
                # Problematic frame:
                # J 42 c2 org.lwjgl.system.JNI.callPV(IJ)V (10 bytes) @ 0x000000011234
                Java frames:
                J 42 c2 org.lwjgl.system.JNI.callPV(IJ)V (10 bytes) @ 0x000000011234
                j  org.lwjgl.opengl.GL11C.glBindTexture(II)V+4
                j  net.minecraft.client.Minecraft.handler$zba000$puremetal$onRender()V+8
                j  com.example.glmod.client.CustomRenderer.drawHud()V+12
                """);
            var a11 = DirectGlCrashAttribution.checkDirectories(List.of(dir11), dir11.resolve("m11.txt"), 9200L, now, resolver);
            require(a11 != null && "glmod".equals(a11.modId()),
                "case 11 neutral JNI problematic frame and puremetal mixin frame skip: " + a11);

            System.out.println("ALL DIRECT-GL CRASH ATTRIBUTION TESTS PASSED (11/11)");
        } finally {
            try (var walk = Files.walk(temp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }
}

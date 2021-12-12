/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.fabricmc.loom.configuration.providers.forge.fg2;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.fabricmc.shade.java.util.jar.Pack200;
import org.gradle.api.Project;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.nothome.delta.GDiffPatcher;

import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;

public class FG2TaskApplyBinPatches {

    private static final HashMap<String, ClassPatch> patchlist = Maps.newHashMap();
    private static final GDiffPatcher                patcher   = new GDiffPatcher();

    private static Project project;

    public static void doTask(Project project, File inJar, File patches, File outjar, String side) throws IOException
    {
        FG2TaskApplyBinPatches.project = project;
        setup(patches, side);

        if (outjar.exists())
        {
            outjar.delete();
        }

        ZipFile in = new ZipFile(inJar);
        ZipInputStream classesIn = new ZipInputStream(new FileInputStream(inJar));
        final ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outjar)));
        final HashSet<String> entries = new HashSet<String>();

        try
        {
            // DO PATCHES
            log("Patching Class:");
            for (ZipEntry e : Collections.list(in.entries()))
            {
                if (e.getName().contains("META-INF"))
                    continue;

                if (e.isDirectory())
                {
                    out.putNextEntry(e);
                }
                else
                {
                    ZipEntry n = new ZipEntry(e.getName());
                    n.setTime(e.getTime());
                    out.putNextEntry(n);

                    byte[] data = ByteStreams.toByteArray(in.getInputStream(e));
                    ClassPatch patch = patchlist.get(e.getName().replace('\\', '/'));

                    if (patch != null)
                    {
                        log("\t%s (%s) (input size %d)", patch.targetClassName, patch.sourceClassName, data.length);
                        int inputChecksum = adlerHash(data);
                        if (patch.inputChecksum != inputChecksum)
                        {
                            throw new RuntimeException(String.format("There is a binary discrepency between the expected input class %s (%s) and the actual class. Checksum on disk is %x, in patch %x. Things are probably about to go very wrong. Did you put something into the jar file?", patch.targetClassName, patch.sourceClassName, inputChecksum, patch.inputChecksum));
                        }
                        synchronized (patcher)
                        {
                            data = patcher.patch(data, patch.patch);
                        }
                    }

                    out.write(data);
                }

                // add the names to the hashset
                entries.add(e.getName());
            }

            // COPY DATA
            ZipEntry entry = null;
            while ((entry = classesIn.getNextEntry()) != null)
            {
                if (entries.contains(entry.getName()))
                    continue;

                out.putNextEntry(entry);
                out.write(ByteStreams.toByteArray(classesIn));
                entries.add(entry.getName());
            }
        }
        finally
        {
            classesIn.close();
            in.close();
            out.close();
        }
    }

    private static int adlerHash(byte[] input)
    {
        Adler32 hasher = new Adler32();
        hasher.update(input);
        return (int) hasher.getValue();
    }

    public static void setup(File patches, String side)
    {
        Pattern matcher = Pattern.compile(String.format("binpatch/%s/.*.binpatch", side));

        JarInputStream jis;
        try
        {
            LzmaInputStream binpatchesDecompressed = new LzmaInputStream(new FileInputStream(patches), new Decoder());
            ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
            JarOutputStream jos = new JarOutputStream(jarBytes);
            Pack200.newUnpacker().unpack(binpatchesDecompressed, jos);
            jis = new JarInputStream(new ByteArrayInputStream(jarBytes.toByteArray()));
        }
        catch (Exception e)
        {
            throw Throwables.propagate(e);
        }

        log("Reading Patches:");
        do
        {
            try
            {
                JarEntry entry = jis.getNextJarEntry();
                if (entry == null)
                {
                    break;
                }

                if (matcher.matcher(entry.getName()).matches())
                {
                    ClassPatch cp = readPatch(entry, jis);
                    patchlist.put(cp.sourceClassName.replace('.', '/') + ".class", cp);
                }
                else
                {
                    log("skipping entry: %s", entry.getName());
                    jis.closeEntry();
                }
            }
            catch (IOException e)
            {}
        } while (true);
        log("Read %d binary patches", patchlist.size());
        log("Patch list :\n\t%s", Joiner.on("\n\t").join(patchlist.entrySet()));
    }

    private static ClassPatch readPatch(JarEntry patchEntry, JarInputStream jis) throws IOException
    {
        log("\t%s", patchEntry.getName());
        ByteArrayDataInput input = ByteStreams.newDataInput(ByteStreams.toByteArray(jis));

        String name = input.readUTF();
        String sourceClassName = input.readUTF();
        String targetClassName = input.readUTF();
        boolean exists = input.readBoolean();
        int inputChecksum = 0;
        if (exists)
        {
            inputChecksum = input.readInt();
        }
        int patchLength = input.readInt();
        byte[] patchBytes = new byte[patchLength];
        input.readFully(patchBytes);

        return new ClassPatch(name, sourceClassName, targetClassName, exists, inputChecksum, patchBytes);
    }

    private static void log(String format, Object... args)
    {
        project.getLogger().info(String.format(format, args));
    }

    public static class ClassPatch
    {
        public final String  name;
        public final String  sourceClassName;
        public final String  targetClassName;
        public final boolean existsAtTarget;
        public final byte[]  patch;
        public final int     inputChecksum;

        public ClassPatch(String name, String sourceClassName, String targetClassName, boolean existsAtTarget, int inputChecksum, byte[] patch)
        {
            this.name = name;
            this.sourceClassName = sourceClassName;
            this.targetClassName = targetClassName;
            this.existsAtTarget = existsAtTarget;
            this.inputChecksum = inputChecksum;
            this.patch = patch;
        }

        @Override
        public String toString()
        {
            return String.format("%s : %s => %s (%b) size %d", name, sourceClassName, targetClassName, existsAtTarget, patch.length);
        }
    }
}

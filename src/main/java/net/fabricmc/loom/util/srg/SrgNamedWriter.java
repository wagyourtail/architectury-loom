/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util.srg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.impl.MappingSetModelFactoryImpl;
import org.cadixdev.lorenz.impl.model.InnerClassMappingImpl;
import org.cadixdev.lorenz.impl.model.TopLevelClassMappingImpl;
import org.cadixdev.lorenz.io.srg.SrgWriter;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.gradle.api.logging.Logger;

import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.tree.MappingTree;

public class SrgNamedWriter {
	public static void writeTo(Logger logger, Path srgFile, MappingTree mappings, String from, String to) throws IOException {
		Files.deleteIfExists(srgFile);

		try (SrgWriter writer = new SrgWriter(Files.newBufferedWriter(srgFile))) {
			try (TinyMappingsReader reader = new TinyMappingsReader(mappings, from, to)) {
				writer.write(reader.read(MappingSet.create(new ClassesAlwaysHaveDeobfNameFactory())));
			}
		}
	}

	/**
	 * Legacy Forge's FMLDeobfuscatingRemapper requires class mappings, even if they are identity maps, but such
	 * mappings are filtered out by the SrgWriter. To get around that, we create a custom mapping set which always
	 * claims to have deobfuscated names set for classes.
	 */
	private static class ClassesAlwaysHaveDeobfNameFactory extends MappingSetModelFactoryImpl {
		@Override
		public TopLevelClassMapping createTopLevelClassMapping(MappingSet parent, String obfuscatedName, String deobfuscatedName) {
			return new TopLevelClassMappingImpl(parent, obfuscatedName, deobfuscatedName) {
				@Override
				public boolean hasDeobfuscatedName() {
					return true;
				}
			};
		}

		@Override
		public InnerClassMapping createInnerClassMapping(ClassMapping parent, String obfuscatedName, String deobfuscatedName) {
			return new InnerClassMappingImpl(parent, obfuscatedName, deobfuscatedName) {
				@Override
				public boolean hasDeobfuscatedName() {
					return true;
				}
			};
		}
	}
}

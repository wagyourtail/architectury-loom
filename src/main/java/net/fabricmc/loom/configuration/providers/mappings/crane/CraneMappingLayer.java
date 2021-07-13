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

package net.fabricmc.loom.configuration.providers.mappings.crane;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import dev.architectury.mappingslayers.api.mutable.MutableClassDef;
import dev.architectury.mappingslayers.api.mutable.MutableFieldDef;
import dev.architectury.mappingslayers.api.mutable.MutableMethodDef;
import dev.architectury.mappingslayers.api.mutable.MutableParameterDef;
import dev.architectury.mappingslayers.api.mutable.MutableTinyTree;
import dev.architectury.mappingslayers.api.utils.MappingsUtils;
import org.apache.commons.io.IOUtils;

import net.fabricmc.loom.configuration.providers.mappings.MappingLayer;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;

public record CraneMappingLayer(File craneJar) implements MappingLayer {
	private static final String TINY_FILE_NAME = "crane.tiny";

	@Override
	public void visit(MappingVisitor visitor) throws IOException {
		try (FileSystemUtil.FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(craneJar().toPath(), false)) {
			try (BufferedReader reader = Files.newBufferedReader(fs.get().getPath(TINY_FILE_NAME), StandardCharsets.UTF_8)) {
				// can't use this, it requires 2 namespaces
				// Tiny2Reader.read(reader, mappingVisitor);
				MutableTinyTree tree = MappingsUtils.deserializeFromString(IOUtils.toString(reader));

				do {
					if (visitor.visitHeader()) {
						visitor.visitNamespaces(tree.getMetadata().getNamespaces().get(0), Collections.emptyList());
					}

					if (visitor.visitContent()) {
						for (MutableClassDef classDef : tree.getClassesMutable()) {
							if (visitor.visitClass(classDef.getName(0))) {
								if (!visitor.visitElementContent(MappedElementKind.CLASS)) {
									return;
								}

								for (MutableFieldDef fieldDef : classDef.getFieldsMutable()) {
									if (visitor.visitField(fieldDef.getName(0), fieldDef.getDescriptor(0))) {
										if (!visitor.visitElementContent(MappedElementKind.FIELD)) {
											return;
										}

										if (fieldDef.getComment() != null) {
											visitor.visitComment(MappedElementKind.FIELD, fieldDef.getComment());
										}
									}
								}

								for (MutableMethodDef methodDef : classDef.getMethodsMutable()) {
									if (visitor.visitMethod(methodDef.getName(0), methodDef.getDescriptor(0))) {
										if (!visitor.visitElementContent(MappedElementKind.METHOD)) {
											return;
										}

										for (MutableParameterDef parameterDef : methodDef.getParametersMutable()) {
											if (visitor.visitMethodArg(parameterDef.getLocalVariableIndex(), parameterDef.getLocalVariableIndex(), parameterDef.getName(0))) {
												if (!visitor.visitElementContent(MappedElementKind.METHOD_ARG)) {
													return;
												}

												if (parameterDef.getComment() != null) {
													visitor.visitComment(MappedElementKind.METHOD_ARG, parameterDef.getComment());
												}
											}
										}

										if (methodDef.getComment() != null) {
											visitor.visitComment(MappedElementKind.METHOD, methodDef.getComment());
										}
									}
								}

								if (classDef.getComment() != null) {
									visitor.visitComment(MappedElementKind.FIELD, classDef.getComment());
								}
							}
						}
					}
				} while (!visitor.visitEnd());
			}
		}
	}
}

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

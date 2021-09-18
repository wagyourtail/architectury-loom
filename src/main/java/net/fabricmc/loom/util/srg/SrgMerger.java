/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.MoreObjects;
import org.apache.commons.io.IOUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.stitch.commands.tinyv2.TinyClass;
import net.fabricmc.stitch.commands.tinyv2.TinyField;
import net.fabricmc.stitch.commands.tinyv2.TinyFile;
import net.fabricmc.stitch.commands.tinyv2.TinyHeader;
import net.fabricmc.stitch.commands.tinyv2.TinyMethod;
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer;

/**
 * Utilities for merging SRG mappings.
 *
 * @author Juuz
 */
public final class SrgMerger {
	/**
	 * Merges SRG mappings with a tiny mappings tree through the obf names.
	 *
	 * @param srg     the SRG file in .tsrg format
	 * @param tiny    the tiny file
	 * @param out     the output file, will be in tiny v2
	 * @param lenient whether to ignore missing tiny mapping
	 * @throws IOException      if an IO error occurs while reading or writing the mappings
	 * @throws MappingException if the input tiny tree's default namespace is not 'official'
	 *                          or if an element mentioned in the SRG file does not have tiny mappings
	 */
	public static void mergeSrg(Supplier<Path> mojmap, Path srg, Path tiny, Path out, boolean lenient) throws IOException, MappingException {
		Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs = new HashMap<>();
		MappingSet arr = readSrg(srg, mojmap, addRegardlessSrgs);
		MemoryMappingTree foss = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(tiny)) {
			MappingReader.read(reader, foss);
		}

		List<String> namespaces = Stream.concat(Stream.of(foss.getSrcNamespace()), foss.getDstNamespaces().stream()).collect(Collectors.toList());
		namespaces.add(1, "srg");

		if (!"official".equals(namespaces.get(0))) {
			throw new MappingException("Mapping file " + tiny + " does not have the 'official' namespace as the default!");
		}

		TinyHeader header = new TinyHeader(namespaces, 2, 0, Collections.emptyMap());

		List<TinyClass> classes = new ArrayList<>();

		for (TopLevelClassMapping klass : arr.getTopLevelClassMappings()) {
			classToTiny(addRegardlessSrgs, foss, namespaces, klass, classes::add, lenient);
		}

		TinyFile file = new TinyFile(header, classes);
		TinyV2Writer.write(file, out);
	}

	private static MappingSet readSrg(Path srg, Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs)
			throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2")) {
				return readTsrg2(content, mojmap, addRegardlessSrgs);
			} else {
				try (TSrgReader srgReader = new TSrgReader(new StringReader(content))) {
					return srgReader.read();
				}
			}
		}
	}

	private static MappingSet readTsrg2(String content, Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs)
			throws IOException {
		MappingSet set;

		try (Tsrg2Utils.MappingsIO2LorenzWriter lorenzWriter = new Tsrg2Utils.MappingsIO2LorenzWriter(0, false)) {
			TsrgReader.read(new StringReader(content), lorenzWriter);
			set = lorenzWriter.read();
			MemoryMappingTree mojmapTree = readTsrg2ToTinyTree(mojmap.get());

			for (MappingTree.ClassMapping classDef : mojmapTree.getClasses()) {
				for (MappingTree.MethodMapping methodDef : classDef.getMethods()) {
					String name = methodDef.getSrcName();

					if (name.indexOf('<') != 0 && name.equals(methodDef.getDstName(0))) {
						addRegardlessSrgs.computeIfAbsent(classDef.getSrcName(), $ -> new ArrayList<>()).add(methodDef);
					}
				}

				for (MappingTree.FieldMapping fieldDef : classDef.getFields()) {
					if (fieldDef.getSrcName().equals(fieldDef.getDstName(0))) {
						addRegardlessSrgs.computeIfAbsent(classDef.getSrcName(), $ -> new ArrayList<>()).add(fieldDef);
					}
				}
			}
		}

		return set;
	}

	private static MemoryMappingTree readTsrg2ToTinyTree(Path path) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			MappingReader.read(reader, tree);
		}

		return tree;
	}

	private static void classToTiny(Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, MappingTree foss, List<String> namespaces, ClassMapping<?, ?> klass, Consumer<TinyClass> classConsumer, boolean lenient) {
		String obf = klass.getFullObfuscatedName();
		String srg = klass.getFullDeobfuscatedName();
		MappingTree.ClassMapping classDef = foss.getClass(obf);

		if (classDef == null) {
			if (lenient) {
				return;
			} else {
				throw new MappingException("Missing class: " + obf + " (srg: " + srg + ")");
			}
		}

		List<String> classNames = CollectionUtil.map(
				namespaces,
				namespace -> "srg".equals(namespace) ? srg : classDef.getName(namespace)
		);

		List<TinyMethod> methods = new ArrayList<>();
		List<TinyField> fields = new ArrayList<>();

		for (MethodMapping method : klass.getMethodMappings()) {
			MappingTree.MethodMapping def = CollectionUtil.find(
					classDef.getMethods(),
					m -> m.getName("official").equals(method.getObfuscatedName()) && m.getDesc("official").equals(method.getObfuscatedDescriptor())
			).orElse(null);

			if (def == null) {
				if (tryMatchRegardlessSrgs(addRegardlessSrgs, namespaces, obf, methods, method)) continue;

				if (!lenient) {
					throw new MappingException("Missing method: " + method.getFullObfuscatedName() + " (srg: " + method.getFullDeobfuscatedName() + ")");
				}

				continue;
			}

			List<String> methodNames = CollectionUtil.map(
					namespaces,
					namespace -> "srg".equals(namespace) ? method.getDeobfuscatedName() : def.getName(namespace)
			);

			methods.add(new TinyMethod(
					def.getDesc("official"), methodNames,
					/* parameters */ Collections.emptyList(),
					/* locals */ Collections.emptyList(),
					/* comments */ Collections.emptyList()
			));
		}

		for (FieldMapping field : klass.getFieldMappings()) {
			MappingTree.FieldMapping def = CollectionUtil.find(
					classDef.getFields(),
					f -> f.getName("official").equals(field.getObfuscatedName())
			).orElse(nullOrThrow(lenient, () -> new MappingException("Missing field: " + field.getFullObfuscatedName() + " (srg: " + field.getFullDeobfuscatedName() + ")")));

			if (def == null) continue;

			List<String> fieldNames = CollectionUtil.map(
					namespaces,
					namespace -> "srg".equals(namespace) ? field.getDeobfuscatedName() : def.getName(namespace)
			);

			fields.add(new TinyField(def.getDesc("official"), fieldNames, Collections.emptyList()));
		}

		TinyClass tinyClass = new TinyClass(classNames, methods, fields, Collections.emptyList());
		classConsumer.accept(tinyClass);

		for (InnerClassMapping innerKlass : klass.getInnerClassMappings()) {
			classToTiny(addRegardlessSrgs, foss, namespaces, innerKlass, classConsumer, lenient);
		}
	}

	private static boolean tryMatchRegardlessSrgs(Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, List<String> namespaces, String obf,
			List<TinyMethod> methods, MethodMapping method) {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrgs.get(obf);

		if (!method.getDeobfuscatedName().equals(method.getObfuscatedName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTreeView.MethodMappingView && descriptored.getSrcName().equals(method.getObfuscatedName()) && descriptored.getSrcDesc().equals(method.getObfuscatedDescriptor())) {
					List<String> methodNames = CollectionUtil.map(
							namespaces,
							namespace -> "srg".equals(namespace) ? method.getDeobfuscatedName() : method.getObfuscatedName()
					);

					methods.add(new TinyMethod(
							method.getObfuscatedDescriptor(), methodNames,
							/* parameters */ Collections.emptyList(),
							/* locals */ Collections.emptyList(),
							/* comments */ Collections.emptyList()
					));
					return true;
				}
			}
		}

		return false;
	}

	@Nullable
	private static <T, X extends Exception> T nullOrThrow(boolean lenient, Supplier<X> exception) throws X {
		if (lenient) {
			return null;
		} else {
			throw exception.get();
		}
	}
}

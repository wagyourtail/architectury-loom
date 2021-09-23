/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.MoreObjects;
import org.apache.commons.io.IOUtils;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

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
	public static void mergeSrg(Logger logger, Supplier<Path> mojmap, Path srg, Path tiny, Path out, boolean lenient) throws IOException, MappingException {
		MemoryMappingTree tree = mergeSrg(logger, mojmap, srg, tiny, lenient);

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out), false)) {
			tree.accept(writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static MemoryMappingTree mergeSrg(Logger logger, Supplier<Path> mojmap, Path srg, Path tiny, boolean lenient) throws IOException, MappingException {
		Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs = new HashMap<>();
		MemoryMappingTree arr = readSrg(srg, mojmap, addRegardlessSrgs);
		MemoryMappingTree foss = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(tiny)) {
			MappingReader.read(reader, foss);
		}

		if (!"official".equals(foss.getSrcNamespace())) {
			throw new MappingException("Mapping file " + tiny + " does not have the 'official' namespace as the default!");
		}

		MemoryMappingTree output = new MemoryMappingTree();
		output.visitNamespaces(foss.getSrcNamespace(), Stream.concat(Stream.of("srg"), foss.getDstNamespaces().stream()).collect(Collectors.toList()));
		RegularAsFlatMappingVisitor flatMappingVisitor = new RegularAsFlatMappingVisitor(output);

		for (MappingTree.ClassMapping klass : arr.getClasses()) {
			classToTiny(logger, addRegardlessSrgs, foss, klass, output, flatMappingVisitor, lenient);
		}

		return output;
	}

	private static MemoryMappingTree readSrg(Path srg, Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs)
			throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2") && mojmap != null) {
				addRegardlessSrgs(mojmap, addRegardlessSrgs);
			}

			MemoryMappingTree tsrg = new MemoryMappingTree();
			TsrgReader.read(new StringReader(content), tsrg);
			return tsrg;
		}
	}

	private static void addRegardlessSrgs(Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs) throws IOException {
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

	private static MemoryMappingTree readTsrg2ToTinyTree(Path path) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			MappingReader.read(reader, tree);
		}

		return tree;
	}

	private static void classToTiny(Logger logger, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, MappingTree foss, MappingTree.ClassMapping klass, MappingTree output, FlatMappingVisitor flatOutput, boolean lenient)
			throws IOException {
		String obf = klass.getSrcName();
		String srg = klass.getDstName(0);
		MappingTree.ClassMapping classDef = foss.getClass(obf);

		if (classDef == null) {
			if (lenient) {
				return;
			} else {
				throw new MappingException("Missing class: " + obf + " (srg: " + srg + ")");
			}
		}

		List<String> classNames = CollectionUtil.map(
				output.getDstNamespaces(),
				namespace -> "srg".equals(namespace) ? srg : classDef.getName(namespace)
		);

		flatOutput.visitClass(obf, classNames.toArray(new String[0]));

		for (MappingTree.MethodMapping method : klass.getMethods()) {
			MappingTree.MethodMapping def = CollectionUtil.find(
					classDef.getMethods(),
					m -> m.getName("official").equals(method.getSrcName()) && m.getDesc("official").equals(method.getSrcDesc())
			).orElse(null);

			if (def == null) {
				if (tryMatchRegardlessSrgsMethod(addRegardlessSrgs, obf, output, flatOutput, method)) continue;

				if (!lenient) {
					throw new MappingException("Missing method: " + method.getSrcName() + " (srg: " + method.getDstName(0) + ")");
				}

				continue;
			}

			List<String> methodNames = CollectionUtil.map(
					output.getDstNamespaces(),
					namespace -> "srg".equals(namespace) ? method.getDstName(0) : def.getName(namespace)
			);

			flatOutput.visitMethod(obf, def.getName("official"), def.getDesc("official"), methodNames.toArray(new String[0]));
		}

		for (MappingTree.FieldMapping field : klass.getFields()) {
			MappingTree.FieldMapping def = CollectionUtil.find(
					classDef.getFields(),
					f -> f.getName("official").equals(field.getSrcName())
			).orElse(nullOrThrow(lenient, () -> new MappingException("Missing field: " + field.getSrcName() + " (srg: " + field.getDstName(0) + ")")));

			if (def == null) {
				if (tryMatchRegardlessSrgsField(addRegardlessSrgs, obf, output, flatOutput, field)) continue;

				continue;
			}

			List<String> fieldNames = CollectionUtil.map(
					output.getDstNamespaces(),
					namespace -> "srg".equals(namespace) ? field.getDstName(0) : def.getName(namespace)
			);

			flatOutput.visitField(obf, def.getName("official"), def.getDesc("official"), fieldNames.toArray(new String[0]));
		}
	}

	private static boolean tryMatchRegardlessSrgsMethod(Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, String obf,
			MappingTree output, FlatMappingVisitor flatOutput, MappingTree.MethodMapping method) throws IOException {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrgs.get(obf);

		if (!method.getDstName(0).equals(method.getSrcName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTree.MethodMapping && descriptored.getSrcName().equals(method.getSrcName()) && descriptored.getSrcDesc().equals(method.getSrcDesc())) {
					List<String> methodNames = CollectionUtil.map(
							output.getDstNamespaces(),
							namespace -> "srg".equals(namespace) ? method.getDstName(0) : method.getSrcName()
					);

					flatOutput.visitMethod(obf, method.getSrcName(), method.getSrcDesc(), methodNames.toArray(new String[0]));
					return true;
				}
			}
		}

		return false;
	}

	private static boolean tryMatchRegardlessSrgsField(Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, String obf,
			MappingTree output, FlatMappingVisitor flatOutput, MappingTree.FieldMapping field) throws IOException {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrgs.get(obf);

		if (!field.getDstName(0).equals(field.getSrcName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTree.FieldMapping && descriptored.getSrcName().equals(field.getSrcName())) {
					List<String> fieldNames = CollectionUtil.map(
							output.getDstNamespaces(),
							namespace -> "srg".equals(namespace) ? field.getDstName(0) : field.getSrcName()
					);

					flatOutput.visitField(obf, field.getSrcName(), field.getSrcDesc(), fieldNames.toArray(new String[0]));
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
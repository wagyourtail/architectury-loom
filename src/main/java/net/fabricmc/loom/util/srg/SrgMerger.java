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
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.MoreObjects;
import org.apache.commons.io.IOUtils;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
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
		Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs = new HashMap<>();
		MemoryMappingTree arr = readSrg(srg, mojmap, addRegardlessSrgs);
		addRegardlessSrgs.clear();
		MemoryMappingTree foss = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(tiny)) {
			MappingReader.read(reader, foss);
		}

		if (!"official".equals(foss.getSrcNamespace())) {
			throw new MappingException("Mapping file " + tiny + " does not have the 'official' namespace as the default!");
		}

		MemoryMappingTree output = new MemoryMappingTree();
		output.visitNamespaces(foss.getSrcNamespace(), Stream.concat(foss.getDstNamespaces().stream(), Stream.of("srg")).collect(Collectors.toList()));
		RegularAsFlatMappingVisitor flatMappingVisitor = new RegularAsFlatMappingVisitor(output);

		for (MappingTree.ClassMapping klass : arr.getClasses()) {
			classToTiny(logger,  addRegardlessSrgs, klass, foss, flatMappingVisitor, output, lenient);
		}

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out), false)) {
			MappingDstNsReorder reorder = new MappingDstNsReorder(writer, Stream.concat(Stream.of("srg"), foss.getDstNamespaces().stream()).collect(Collectors.toList()));
			output.accept(reorder);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static MemoryMappingTree readSrg(Path srg, Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs)
			throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2")) {
				return readTsrg2(content, mojmap, addRegardlessSrgs);
			} else {
				MemoryMappingTree tsrg = new MemoryMappingTree();
				TsrgReader.read(new StringReader(content), tsrg);
				return tsrg;
			}
		}
	}

	private static MemoryMappingTree readTsrg2(String content, Supplier<Path> mojmap, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs)
			throws IOException {
		MemoryMappingTree tsrg2 = new MemoryMappingTree();
		TsrgReader.read(new StringReader(content), tsrg2);
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

		return tsrg2;
	}

	private static MemoryMappingTree readTsrg2ToTinyTree(Path path) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			MappingReader.read(reader, tree);
		}

		return tree;
	}

	private static void classToTiny(Logger logger, Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, MappingTree.ClassMapping klass, MemoryMappingTree foss, RegularAsFlatMappingVisitor flatOutput, MemoryMappingTree output, boolean lenient)
			throws IOException {
		String obf = klass.getSrcName();
		String srg = klass.getDstName(0);
		MappingTree.ClassMapping fossClass = foss.getClass(obf);
		int srgId = output.getNamespaceId("srg");

		if (fossClass == null) {
			if (lenient) {
				return;
			} else {
				throw new MappingException("Missing class: " + obf + " (srg: " + srg + ")");
			}
		}

		flatOutput.visitClass(obf, output.getDstNamespaces().stream().map(ns ->
				ns.equals("srg") ? srg : fossClass.getName(ns)).toArray(String[]::new));

		for (MappingTree.MethodMapping method : klass.getMethods()) {
			MappingTree.MethodMapping fossMethod = CollectionUtil.find(
					fossClass.getMethods(),
					m -> m.getSrcName().equals(method.getSrcName()) && m.getSrcDesc().equals(method.getSrcDesc())
			).orElse(null);

			if (fossMethod == null) {
				if (tryMatchRegardlessSrgs(addRegardlessSrgs, obf, method)) {
					flatOutput.visitMethod(obf, method.getSrcName(), method.getSrcDesc(), output.getDstNamespaces().stream().map(ns ->
							ns.equals("srg") ? method.getDstName(0) : method.getSrcName()).toArray(String[]::new));
					continue;
				}

				if (!lenient) {
					throw new MappingException("Missing method: " + method.getSrcName() + " (srg: " + method.getDstName(0) + ")");
				}

				logger.debug("Missing method: " + method.getSrcName() + method.getSrcDesc() + " (srg: " + method.getDstName(0) + ") " + fossClass.getMethods().size() + " methods in the original class");

				continue;
			}

			flatOutput.visitMethod(obf, fossMethod.getSrcName(), fossMethod.getSrcDesc(), output.getDstNamespaces().stream().map(ns ->
					ns.equals("srg") ? method.getDstName(0) : fossMethod.getName(ns)).toArray(String[]::new));

			for (MappingTree.MethodArgMapping arg : fossMethod.getArgs()) {
				flatOutput.visitMethodArg(obf, fossMethod.getSrcName(), fossMethod.getSrcDesc(), arg.getArgPosition(),
						arg.getLvIndex(), arg.getSrcName(), output.getDstNamespaces().stream().map(ns ->
								ns.equals("srg") ? arg.getName("named") : arg.getName(ns)).toArray(String[]::new));
			}
		}

		for (MappingTree.FieldMapping field : klass.getFields()) {
			MappingTree.FieldMapping fossField = CollectionUtil.find(
					fossClass.getFields(),
					f -> f.getSrcName().equals(field.getSrcName())
			).orElse(nullOrThrow(lenient, () -> new MappingException("Missing field: " + field.getSrcName() + " (srg: " + field.getDstName(0) + ")")));

			if (fossField == null) {
				logger.debug("Missing field: " + field.getSrcName() + " (srg: " + field.getDstName(0) + ") " + fossClass.getFields().size() + " fields in the original class");
				continue;
			}

			flatOutput.visitField(obf, fossField.getSrcName(), fossField.getSrcDesc(), output.getDstNamespaces().stream().map(ns ->
					ns.equals("srg") ? field.getDstName(0) : fossField.getName(ns)).toArray(String[]::new));
		}
	}

	private static boolean tryMatchRegardlessSrgs(Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrgs, String obf, MappingTree.MethodMapping method) {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrgs.get(obf);

		if (!Objects.equals(method.getDstName(0), method.getSrcName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTreeView.MethodMappingView && descriptored.getSrcName().equals(method.getSrcName()) && descriptored.getSrcDesc().equals(method.getSrcDesc())) {
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

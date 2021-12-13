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

package net.fabricmc.loom.configuration.providers.forge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.mappings.GradleMappingContext;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.srg.Tsrg2Utils;
import net.fabricmc.loom.util.srg.Tsrg2Writer;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class SrgProvider extends DependencyProvider {
	private Path srg;
	private Boolean isTsrgV2;
	private boolean isLegacy;
	private Path mergedMojangRaw;
	private Path mergedMojang;
	private Path mergedMojangTrimmed;
	private static Path mojmapTsrg;
	private static Path mojmapTsrg2;

	public SrgProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		init(dependency.getDependency().getVersion());

		if (!Files.exists(srg) || isRefreshDeps()) {
			Path srgZip = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve srg")).toPath();

			try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + srgZip.toUri()), ImmutableMap.of("create", false))) {
				try {
					Files.copy(fs.getPath("config", "joined.tsrg"), srg, StandardCopyOption.REPLACE_EXISTING);
				} catch (NoSuchFileException e) {
					if (getExtension().getForgeProvider().getFG() == ForgeProvider.FG_VERSION.ONE_SEVEN) {
						Files.copy(fs.getPath("conf", "packaged.srg"), srg, StandardCopyOption.REPLACE_EXISTING);
					} else {
						Files.copy(fs.getPath("joined.srg"), srg, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}

		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String line = reader.readLine();
			isTsrgV2 = line.startsWith("tsrg2");
			isLegacy = line.startsWith("PK:") || line.startsWith("CL:");
		}

		if (isTsrgV2) {
			if (!Files.exists(mergedMojangRaw) || !Files.exists(mergedMojang) || !Files.exists(mergedMojangTrimmed) || isRefreshDeps()) {
				Stopwatch stopwatch = Stopwatch.createStarted();
				getProject().getLogger().lifecycle(":merging mappings (InstallerTools, srg + mojmap)");
				PrintStream out = System.out;
				PrintStream err = System.err;

				if (getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) >= 0) {
					System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
					System.setErr(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
				}

				Files.deleteIfExists(mergedMojangRaw);
				Files.deleteIfExists(mergedMojang);
				net.minecraftforge.installertools.ConsoleTool.main(new String[] {
						"--task",
						"MERGE_MAPPING",
						"--left",
						getSrg().toAbsolutePath().toString(),
						"--right",
						getMojmapTsrg2(getProject(), getExtension()).toAbsolutePath().toString(),
						"--classes",
						"--output",
						mergedMojangRaw.toAbsolutePath().toString()
				});

				MemoryMappingTree tree = new MemoryMappingTree();
				MappingReader.read(new StringReader(FileUtils.readFileToString(mergedMojangRaw.toFile(), StandardCharsets.UTF_8)), new FieldDescWrappingVisitor(tree));
				Files.writeString(mergedMojang, Tsrg2Writer.serialize(tree), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

				for (MappingTree.ClassMapping classDef : tree.getClasses()) {
					for (MappingTree.MethodMapping methodDef : classDef.getMethods()) {
						methodDef.getArgs().clear();
					}
				}

				Files.writeString(mergedMojangTrimmed, Tsrg2Writer.serialize(tree), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

				if (getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) >= 0) {
					System.setOut(out);
					System.setErr(err);
				}

				getProject().getLogger().lifecycle(":merged mappings (InstallerTools, srg + mojmap) in " + stopwatch.stop());
			}
		}
	}

	// Read mojmap and apply field descs to the tsrg2
	private class FieldDescWrappingVisitor extends ForwardingMappingVisitor {
		private final Map<FieldKey, String> fieldDescMap = new HashMap<>();
		private String lastClass;

		protected FieldDescWrappingVisitor(MappingVisitor next) throws IOException {
			super(next);
			MemoryMappingTree mojmap = new MemoryMappingTree();
			MappingReader.read(getMojmapTsrg2(getProject(), getExtension()), mojmap);

			for (MappingTree.ClassMapping classMapping : mojmap.getClasses()) {
				for (MappingTree.FieldMapping fieldMapping : classMapping.getFields()) {
					fieldDescMap.put(new FieldKey(classMapping.getSrcName(), fieldMapping.getSrcName()), fieldMapping.getSrcDesc());
				}
			}
		}

		@Override
		public boolean visitClass(String srcName) throws IOException {
			if (super.visitClass(srcName)) {
				this.lastClass = srcName;
				return true;
			} else {
				return false;
			}
		}

		@Override
		public boolean visitField(String srcName, String srcDesc) throws IOException {
			if (srcDesc == null) {
				srcDesc = fieldDescMap.get(new FieldKey(lastClass, srcName));
			}

			return super.visitField(srcName, srcDesc);
		}

		private record FieldKey(String owner, String name) {
		}
	}

	private void init(String version) {
		File dir = getMinecraftProvider().dir("srg/" + version);
		srg = new File(dir, "srg.tsrg").toPath();
		mergedMojangRaw = new File(dir, "srg-mojmap-merged-raw.tsrg").toPath();
		mergedMojang = new File(dir, "srg-mojmap-merged.tsrg").toPath();
		mergedMojangTrimmed = new File(dir, "srg-mojmap-merged-trimmed.tsrg").toPath();
	}

	public Path getSrg() {
		return srg;
	}

	public Path getMergedMojangRaw() {
		if (!isTsrgV2()) throw new IllegalStateException("May not access merged mojmap srg if not on modern Minecraft!");

		return mergedMojangRaw;
	}

	public Path getMergedMojang() {
		if (!isTsrgV2()) throw new IllegalStateException("May not access merged mojmap srg if not on modern Minecraft!");

		return mergedMojang;
	}

	public Path getMergedMojangTrimmed() {
		if (!isTsrgV2()) throw new IllegalStateException("May not access merged mojmap srg if not on modern Minecraft!");

		return mergedMojangTrimmed;
	}

	public boolean isTsrgV2() {
		return isTsrgV2;
	}

	public boolean isLegacy() {
		return isLegacy;
	}

	public static Path getMojmapTsrg(Project project, LoomGradleExtension extension) throws IOException {
		if (mojmapTsrg != null) return mojmapTsrg;

		mojmapTsrg = extension.getMinecraftProvider().dir("forge").toPath().resolve("mojmap.tsrg");

		if (Files.notExists(mojmapTsrg) || LoomGradlePlugin.refreshDeps) {
			try (BufferedWriter writer = Files.newBufferedWriter(mojmapTsrg, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				Tsrg2Utils.writeTsrg(visitor -> visitMojmap(visitor, project),
						MappingsNamespace.NAMED.toString(), false, writer);
			}
		}

		return mojmapTsrg;
	}

	public static Path getMojmapTsrg2(Project project, LoomGradleExtension extension) throws IOException {
		if (mojmapTsrg2 != null) return mojmapTsrg2;

		mojmapTsrg2 = extension.getMinecraftProvider().dir("forge").toPath().resolve("mojmap.tsrg2");

		if (Files.notExists(mojmapTsrg2) || LoomGradlePlugin.refreshDeps) {
			try (BufferedWriter writer = Files.newBufferedWriter(mojmapTsrg2, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				MemoryMappingTree tree = new MemoryMappingTree();
				visitMojmap(tree, project);
				writer.write(Tsrg2Writer.serialize(tree));
			}
		}

		return mojmapTsrg2;
	}

	private static void visitMojmap(MappingVisitor visitor, Project project) {
		GradleMappingContext context = new GradleMappingContext(project, "tmp-mojmap");

		try {
			FileUtils.deleteDirectory(context.workingDirectory("/").toFile());
			MojangMappingLayer layer = new MojangMappingsSpec(() -> true, true).createLayer(context);
			layer.visit(visitor);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			try {
				FileUtils.deleteDirectory(context.workingDirectory("/").toFile());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.SRG;
	}
}

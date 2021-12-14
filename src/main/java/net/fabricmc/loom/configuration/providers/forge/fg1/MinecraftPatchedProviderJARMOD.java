package net.fabricmc.loom.configuration.providers.forge.fg1;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.base.Stopwatch;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;

public class MinecraftPatchedProviderJARMOD extends MinecraftPatchedProvider {
	// step 0: strip minecraft jars
	private File minecraftClientJar;
	private File minecraftServerJar;
	// step 1: jarmod
	private File minecraftClientPatchedJar;
	private File minecraftServerPatchedJar;

	// step 5: merge
	// fields in super

	public MinecraftPatchedProviderJARMOD(Project project) {
		super(project);
	}

	@Override
	public void initFiles() throws IOException {
		super.initFiles();
		File globalCache = getExtension().getForgeProvider().getGlobalCache();
		minecraftClientJar = new File(globalCache, "client-stripped.jar");
		minecraftServerJar = new File(globalCache, "server-stripped.jar");
		minecraftClientPatchedJar = new File(globalCache, "client-patched.jar");
		minecraftServerPatchedJar = new File(globalCache, "server-patched.jar");
	}

	@Override
	protected File[] getGlobalCaches() {
		File[] files = {
				minecraftClientJar,
				minecraftServerJar,
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftClientExtra,
		};

		if (forgeMergedJar != null) {
			files = Arrays.copyOf(files, files.length + 1);
			files[files.length - 1] = forgeMergedJar;
		}

		return files;
	}

	private boolean dirty;
	@Override
	protected void beginTransform() throws Exception {
		this.dirty = false;

		// Step 0: strip the client/server jars
		if (!minecraftClientJar.exists() || !minecraftServerJar.exists()) {
			this.dirty = true;
			stripJars(getProject().getLogger());
		}

		// Step 1: jarmod
		if (!minecraftClientPatchedJar.exists() || !minecraftServerPatchedJar.exists()) {
			this.dirty = true;
			patchJars(getProject().getLogger());
		}
	}

	@Override
	public void endTransform() throws Exception {
		// Step 5: Merge (global)
		if (dirty || !minecraftMergedPatchedAtJar.exists()) {
			mergeJars(getProject().getLogger());
		}
	}

	private void stripJars(Logger logger) throws IOException {
		logger.lifecycle(":stripping jars");
		Set<String> filter = Files.readAllLines(getExtension().getSrgProvider().getSrg(), StandardCharsets.UTF_8).stream()
				.filter(s -> s.startsWith("CL:"))
				.map(s -> s.split(" ")[1] + ".class")
				.collect(Collectors.toSet());
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		SpecialSourceExecutor.stripJar(getProject(), minecraftProvider.minecraftClientJar.toPath(), minecraftClientJar.toPath(), filter);
		SpecialSourceExecutor.stripJar(getProject(), minecraftProvider.getMinecraftServerJar().toPath(), minecraftServerJar.toPath(), filter);
	}

	public enum Environment {
		CLIENT(provider -> provider.minecraftClientJar,
						provider -> provider.minecraftClientPatchedJar
		),
		SERVER(provider -> provider.minecraftServerJar,
						provider -> provider.minecraftServerPatchedJar
		);

		final Function<MinecraftPatchedProviderJARMOD, File> srgJar;
		final Function<MinecraftPatchedProviderJARMOD, File> patchedSrgJar;

		Environment(Function<MinecraftPatchedProviderJARMOD, File> srgJar,
						Function<MinecraftPatchedProviderJARMOD, File> patchedSrgJar) {
			this.srgJar = srgJar;
			this.patchedSrgJar = patchedSrgJar;
		}

		public String side() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private void patchJars(Logger logger) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientJar, minecraftClientPatchedJar, getExtension().getForgeUniversalProvider().getForge().toPath(), "client");
		patchJars(minecraftServerJar, minecraftServerPatchedJar, getExtension().getForgeUniversalProvider().getForge().toPath(), "server");

		ThreadingUtils.run(MinecraftPatchedProviderJARMOD.Environment.values(), environment -> {
			copyMissingClasses(environment.srgJar.apply(this), environment.patchedSrgJar.apply(this));
			deleteParameterNames(environment.patchedSrgJar.apply(this));

			if (getExtension().isForgeAndNotOfficial()) {
				fixParameterAnnotation(environment.patchedSrgJar.apply(this));
			}
		});

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	@Override
	protected void patchJars(File clean, File output, Path patches, String side) throws IOException {
		Files.copy(clean.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
		copyNonMissingClasses(patches.toFile(), output);
	}

	protected void copyNonMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> true, (sourceFS, targetFS, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) {
				Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

				//TODO: hack, remove patched class files from universal jar at this step
				Files.delete(sourcePath);
			}
		});
	}

	@Override
	protected void mergeJars(Logger logger) throws Exception {
		// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
		//  This will change if upstream Loom adds the possibility for separate projects/source sets per environment.

		logger.lifecycle(":merging jars");
		Files.copy(minecraftClientPatchedJar.toPath(), minecraftMergedPatchedAtJar.toPath());

		logger.lifecycle(":copying resources");

		// Copy resources
		if (getExtension().isForgeAndNotOfficial()) {
			// Copy resources
			MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
			copyNonClassFiles(minecraftProvider.minecraftClientJar, minecraftMergedPatchedAtJar);
			copyNonClassFiles(minecraftProvider.getMinecraftServerJar(), minecraftMergedPatchedAtJar);
		}
	}
}

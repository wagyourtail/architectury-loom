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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.util.Constants;

public class PatchProvider extends DependencyProvider {
	public Path clientPatches;
	public Path serverPatches;
	public Path projectCacheFolder;

	public PatchProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		init(dependency.getDependency().getVersion());

		if (getExtension().getForgeProvider().getFG() != ForgeProvider.FG_VERSION.FG3) {
			File forge = getExtension().getForgeUniversalProvider().getForge();

			if (Files.notExists(clientPatches) || isRefreshDeps()) {
				getProject().getLogger().info(":extracting forge patches");

				try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + forge.toURI()), ImmutableMap.of("create", false))) {
					Files.copy(fs.getPath("binpatches.pack.lzma"), clientPatches, StandardCopyOption.REPLACE_EXISTING);
					Files.copy(fs.getPath("binpatches.pack.lzma"), serverPatches, StandardCopyOption.REPLACE_EXISTING);
				}
			}

			return;
		}

		if (Files.notExists(clientPatches) || Files.notExists(serverPatches) || isRefreshDeps()) {
			getProject().getLogger().info(":extracting forge patches");

			Path installerJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException(
							"Could not resolve Forge installer")).toPath();

			try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + installerJar.toUri()), ImmutableMap.of("create", false))) {
				Files.copy(fs.getPath("data", "client.lzma"), clientPatches, StandardCopyOption.REPLACE_EXISTING);
				Files.copy(fs.getPath("data", "server.lzma"), serverPatches, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private void init(String forgeVersion) {
		projectCacheFolder = getMinecraftProvider().dir("forge/" + forgeVersion).toPath();
		clientPatches = projectCacheFolder.resolve("patches-client.lzma");
		serverPatches = projectCacheFolder.resolve("patches-server.lzma");

		try {
			Files.createDirectories(projectCacheFolder);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public Path getProjectCacheFolder() {
		return projectCacheFolder;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_INSTALLER;
	}
}

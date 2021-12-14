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
import java.util.function.Consumer;

import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.util.Constants;

public class ForgeProvider extends DependencyProvider {
	private ForgeVersion version = new ForgeVersion(null);
	private File globalCache;
	private File projectCache;

	private FG_VERSION fgVersion = FG_VERSION.FG3;

	public ForgeProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		version = new ForgeVersion(dependency.getDepString().split(":")[2]);

		if (semVersCompare(version.minecraftVersion, "1.7.2") != -1) {
			getProject().getLogger().info("semV: " + semVersCompare(version.minecraftVersion, "1.7.10"));

			if (semVersCompare(version.minecraftVersion, "1.7.10") != 1) {
				setFg(FG_VERSION.ONE_SEVEN);
			}

			addDependency(dependency.getDepString() + ":userdev", Constants.Configurations.FORGE_USERDEV);
			addDependency(dependency.getDepString() + ":installer", Constants.Configurations.FORGE_INSTALLER);
		} else {
			addDependency(dependency.getDepString() + ":src@zip", Constants.Configurations.FORGE_USERDEV);
			addDependency(dependency.getDepString() + ":installer", Constants.Configurations.FORGE_INSTALLER);

			setFg(FG_VERSION.FG1);
		}
	}

	public ForgeVersion getVersion() {
		return version;
	}

	public File getGlobalCache() {
		if (globalCache == null) {
			globalCache = getMinecraftProvider().dir("forge/" + version.getCombined());
			globalCache.mkdirs();
		}

		return globalCache;
	}

	public File getProjectCache() {
		if (projectCache == null) {
			projectCache = new File(getDirectories().getRootProjectPersistentCache(), getMinecraftProvider().minecraftVersion() + "/forge/" + getExtension().getForgeProvider().getVersion().getCombined() + "/project-" + getProject().getPath().replace(':', '@'));
			projectCache.mkdirs();
		}

		return projectCache;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE;
	}

	public FG_VERSION getFG() {
		return fgVersion;
	}

	public void setFg(FG_VERSION fgVersion) {
		this.fgVersion = fgVersion;
	}

	public static final class ForgeVersion {
		private final String combined;
		private final String minecraftVersion;
		private final String forgeVersion;

		public ForgeVersion(String combined) {
			this.combined = combined;

			if (combined == null) {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = "NO_VERSION";
				return;
			}

			int hyphenIndex = combined.indexOf('-');

			if (hyphenIndex != -1) {
				this.minecraftVersion = combined.substring(0, hyphenIndex);
				this.forgeVersion = combined.substring(hyphenIndex + 1);
			} else {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = combined;
			}
		}

		public String getCombined() {
			return combined;
		}

		public String getMinecraftVersion() {
			return minecraftVersion;
		}

		public String getForgeVersion() {
			return forgeVersion;
		}
	}

	/**
	 * @param v1
	 * @param v2
	 * @return -1 if v2 > v1 else 1 if v1 > v2 else 0
	 */
	public static int semVersCompare(String v1, String v2) {
		String[] v1s = v1.split("\\.");
		String[] v2s = v2.split("\\.");

		for (int i = 0; i < v1s.length; ++i) {
			// off the end of v2 but v1 still has more
			if (i >= v2s.length) {
				return 1;
			}

			if (!v1s[i].equals(v2s[i])) {
				if (Integer.parseInt(v1s[i]) > Integer.parseInt(v2s[i])) {
					return 1;
				}

				return -1;
			}
		}

		return 0;
	}

	public enum FG_VERSION {
		FG3, ONE_TWELVE, FG2, ONE_SEVEN, FG1, PRE_ONE_THREE
	}
}

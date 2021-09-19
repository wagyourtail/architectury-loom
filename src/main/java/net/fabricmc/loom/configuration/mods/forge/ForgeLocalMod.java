package net.fabricmc.loom.configuration.mods.forge;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;

public class ForgeLocalMod implements Named {
	private final Project project;
	private final String name;
	private final List<Supplier<SourceSet>> sourceSets;

	public ForgeLocalMod(Project project, String name, List<Supplier<SourceSet>> sourceSets) {
		this.project = project;
		this.name = name;
		this.sourceSets = sourceSets;
	}

	public void add(Object... sourceSets) {
		for (Object sourceSet : sourceSets) {
			if (sourceSet instanceof SourceSet) {
				this.sourceSets.add(() -> (SourceSet) sourceSet);
			} else {
				this.sourceSets.add(() -> project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().findByName(String.valueOf(sourceSet)));
			}
		}
	}

	@Override
	public String getName() {
		return name;
	}

	public Stream<SourceSet> getSourceSets() {
		return sourceSets.stream().map(Supplier::get);
	}
}
package dev.su5ed.sinytra.connector.locator;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.SecureJar;
import dev.su5ed.sinytra.connector.ConnectorUtil;
import dev.su5ed.sinytra.connector.loader.ConnectorEarlyLoader;
import dev.su5ed.sinytra.connector.loader.ConnectorLoaderModMetadata;
import dev.su5ed.sinytra.connector.transformer.JarTransformer;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModJarMetadata;
import net.minecraftforge.fml.loading.progress.StartupNotificationManager;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModProvider;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.rethrowFunction;
import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;
import static dev.su5ed.sinytra.connector.transformer.JarTransformer.cacheTransformableJar;
import static net.minecraftforge.fml.loading.LogMarkers.SCAN;

public class ConnectorLocator extends AbstractJarFileModProvider implements IDependencyLocator {
    private static final String NAME = "connector_locator";
    private static final String SUFFIX = ".jar";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MethodHandle MJM_INIT = uncheck(() -> MethodHandles.privateLookupIn(ModJarMetadata.class, MethodHandles.lookup()).findConstructor(ModJarMetadata.class, MethodType.methodType(void.class)));

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        if (ConnectorEarlyLoader.hasEncounteredException()) {
            LOGGER.error("Skipping mod scan due to previously encountered error");
            return List.of();
        }
        try {
            return locateFabricMods(loadedMods);
        } catch (EarlyLoadingException e) {
            // Let these pass through
            throw e;
        } catch (Throwable t) {
            // Rethrow other exceptions
            StartupNotificationManager.addModMessage("CONNECTOR LOCATOR ERROR");
            throw ConnectorEarlyLoader.createGenericLoadingException(t, "Fabric mod discovery failed");
        }
    }

    private List<IModFile> locateFabricMods(Iterable<IModFile> loadedMods) {
        LOGGER.debug(SCAN, "Scanning mods dir {} for mods", FMLPaths.MODSDIR.get());
        List<Path> excluded = ModDirTransformerDiscoverer.allExcluded();
        Path tempDir = ConnectorUtil.CONNECTOR_FOLDER.resolve("temp");
        // Get all existing mod ids
        Collection<String> loadedModIds = StreamSupport.stream(loadedMods.spliterator(), false)
            .flatMap(modFile -> Optional.ofNullable(modFile.getModFileInfo()).stream())
            .flatMap(modFileInfo -> modFileInfo.getMods().stream().map(IModInfo::getModId))
            .toList();
        // Discover fabric mod jars
        List<JarTransformer.TransformableJar> discoveredJars = uncheck(() -> Files.list(FMLPaths.MODSDIR.get()))
            .filter(p -> !excluded.contains(p) && StringUtils.toLowerCase(p.getFileName().toString()).endsWith(SUFFIX))
            .sorted(Comparator.comparing(path -> StringUtils.toLowerCase(path.getFileName().toString())))
            .filter(ConnectorLocator::locateFabricModJar)
            .map(rethrowFunction(p -> cacheTransformableJar(p.toFile())))
            .filter(jar -> {
                String modid = jar.modPath().metadata().modMetadata().getId();
                return !ConnectorUtil.DISABLED_MODS.contains(modid) && !loadedModIds.contains(modid);
            })
            .toList();
        // Discover fabric nested mod jars
        List<JarTransformer.TransformableJar> discoveredNestedJars = discoveredJars.stream()
            .flatMap(jar -> {
                SecureJar secureJar = SecureJar.from(jar.input().toPath());
                ConnectorLoaderModMetadata metadata = jar.modPath().metadata().modMetadata();
                return discoverNestedJarsRecursive(tempDir, secureJar, metadata.getJars());
            })
            .toList();
        // Remove mods loaded by FML
        List<JarTransformer.TransformableJar> uniqueJars = handleDuplicateMods(discoveredJars, discoveredNestedJars, loadedModIds);
        // Ensure we have all required dependencies before transforming
        List<JarTransformer.TransformableJar> candidates = DependencyResolver.resolveDependencies(uniqueJars, loadedMods);
        // Get renamer library classpath
        List<Path> renameLibs = StreamSupport.stream(loadedMods.spliterator(), false).map(modFile -> modFile.getSecureJar().getRootPath()).toList();
        // Run jar transformations (or get existing outputs from cache)
        List<JarTransformer.FabricModPath> transformed = JarTransformer.transform(candidates, renameLibs);
        // Skip last step to save time if an error occured during transformation
        if (ConnectorEarlyLoader.hasEncounteredException()) {
            StartupNotificationManager.addModMessage("JAR TRANSFORMATION ERROR");
            LOGGER.error("Cancelling jar discovery due to previous error");
            return List.of();
        }
        // Deal with split packages (thanks modules)
        List<SplitPackageMerger.FilteredModPath> moduleSafeJars = SplitPackageMerger.mergeSplitPackages(transformed, loadedMods);
        return moduleSafeJars.stream()
            .map(mod -> createConnectorModFile(mod, this))
            .toList();
    }

    private static IModFile createConnectorModFile(SplitPackageMerger.FilteredModPath modPath, IModProvider provider) {
        ModJarMetadata mjm = ConnectorUtil.uncheckThrowable(() -> (ModJarMetadata) MJM_INIT.invoke());
        SecureJar modJar = SecureJar.from(Manifest::new, jar -> mjm, modPath.filter(), modPath.paths());
        IModFile mod = new ModFile(modJar, provider, modFile -> ConnectorModMetadataParser.createForgeMetadata(modFile, modPath.metadata().modMetadata()));
        mjm.setModFile(mod);
        return mod;
    }

    private static boolean locateFabricModJar(Path path) {
        SecureJar secureJar = SecureJar.from(path);
        String name = secureJar.name();
        if (secureJar.moduleDataProvider().findFile(ConnectorUtil.MODS_TOML).isPresent()) {
            LOGGER.debug(SCAN, "Skipping jar {} as it contains a mods.toml file", path);
            return false;
        }
        if (secureJar.moduleDataProvider().findFile(ConnectorUtil.FABRIC_MOD_JSON).isPresent()) {
            LOGGER.debug(SCAN, "Found {} mod: {}", ConnectorUtil.FABRIC_MOD_JSON, path);
            return true;
        }
        LOGGER.info(SCAN, "Fabric mod metadata not found in jar {}, ignoring", name);
        return false;
    }

    private static Stream<JarTransformer.TransformableJar> discoverNestedJarsRecursive(Path tempDir, SecureJar secureJar, Collection<NestedJarEntry> jars) {
        return jars.stream()
            .map(entry -> secureJar.getPath(entry.getFile()))
            .filter(Files::exists)
            .flatMap(path -> {
                JarTransformer.TransformableJar jar = uncheck(() -> prepareNestedJar(tempDir, secureJar.getPrimaryPath().getFileName().toString(), path));
                ConnectorLoaderModMetadata metadata = jar.modPath().metadata().modMetadata();
                return Stream.concat(Stream.of(jar), discoverNestedJarsRecursive(tempDir, SecureJar.from(jar.input().toPath()), metadata.getJars()));
            });
    }

    private static JarTransformer.TransformableJar prepareNestedJar(Path tempDir, String parentName, Path path) throws IOException {
        Files.createDirectories(tempDir);

        String parentNameWithoutExt = parentName.split("\\.(?!.*\\.)")[0];
        // Extract JiJ
        Path extracted = tempDir.resolve(parentNameWithoutExt + "$" + path.getFileName().toString());
        ConnectorUtil.cache(path, extracted, () -> Files.copy(path, extracted));

        return uncheck(() -> JarTransformer.cacheTransformableJar(extracted.toFile()));
    }

    // Removes any duplicates from located connector mods, as well as mods that are already located by FML.
    private static List<JarTransformer.TransformableJar> handleDuplicateMods(List<JarTransformer.TransformableJar> rootMods, List<JarTransformer.TransformableJar> nestedMods, Collection<String> loadedModIds) {
        return Stream.concat(rootMods.stream(), nestedMods.stream())
            .filter(jar -> {
                String id = jar.modPath().metadata().modMetadata().getId();
                if (!loadedModIds.contains(id)) {
                    return true;
                }
                else {
                    LOGGER.info(SCAN, "Removing duplicate mod {} in file {}", id, jar.modPath().path().toAbsolutePath());
                    return false;
                }
            })
            .toList();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {}
}

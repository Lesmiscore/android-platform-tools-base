/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.builder.core;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.builder.dependency.DependencyContainer;
import com.android.builder.dependency.JarDependency;
import com.android.builder.dependency.LibraryDependency;
import com.android.builder.internal.MergedNdkConfig;
import com.android.builder.internal.StringHelper;
import com.android.builder.model.ApiVersion;
import com.android.builder.model.BaseConfig;
import com.android.builder.model.ClassField;
import com.android.builder.model.NdkConfig;
import com.android.builder.model.ProductFlavor;
import com.android.builder.model.SigningConfig;
import com.android.builder.model.SourceProvider;
import com.android.ide.common.res2.AssetSet;
import com.android.ide.common.res2.ResourceSet;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Variant configuration.
 */
public class VariantConfiguration {

    // per variant, as is caches some manifest data specific to this variant.
    private final ManifestParser sManifestParser = new DefaultManifestParser();

    /**
     * Full, unique name of the variant in camel case, including BuildType and Flavors (and Test)
     */
    private String mFullName;
    /**
     * Flavor Name of the variant, including all flavors in camel case (starting with a lower
     * case).
     */
    private String mFlavorName;
    /**
     * Full, unique name of the variant, including BuildType, flavors and test, dash separated.
     * (similar to full name but with dashes)
     */
    private String mBaseName;
    /**
     * Unique directory name (can include multiple folders) for the variant, based on build type,
     * flavor and test.
     * This always uses forward slashes ('/') as separator on all platform.
     *
     */
    private String mDirName;

    @NonNull
    private final DefaultProductFlavor mDefaultConfig;
    @NonNull
    private final SourceProvider mDefaultSourceProvider;

    @NonNull
    private final DefaultBuildType mBuildType;
    /** SourceProvider for the BuildType. Can be null */
    @Nullable
    private final SourceProvider mBuildTypeSourceProvider;

    private final List<String> mFlavorDimensionNames = Lists.newArrayList();
    private final List<DefaultProductFlavor> mFlavorConfigs = Lists.newArrayList();
    private final List<SourceProvider> mFlavorSourceProviders = Lists.newArrayList();

    /** Variant specific source provider, may be null */
    @Nullable
    private SourceProvider mVariantSourceProvider;

    /** MultiFlavors specific source provider, may be null */
    @Nullable
    private SourceProvider mMultiFlavorSourceProvider;

    @NonNull
    private final Type mType;
    /** Optional tested config in case type is Type#TEST */
    private final VariantConfiguration mTestedConfig;
    /** An optional output that is only valid if the type is Type#LIBRARY so that the test
     * for the library can use the library as if it was a normal dependency. */
    private LibraryDependency mOutput;

    private DefaultProductFlavor mMergedFlavor;
    private final MergedNdkConfig mMergedNdkConfig = new MergedNdkConfig();

    private final Set<JarDependency> mJars = Sets.newHashSet();

    /** List of direct library dependencies. Each object defines its own dependencies. */
    private final List<LibraryDependency> mDirectLibraries = Lists.newArrayList();

    /** list of all library dependencies in a flat list.
     * The order is based on the order needed to call aapt: earlier libraries override resources
     * of latter ones. */
    private final List<LibraryDependency> mFlatLibraries = Lists.newArrayList();

    /**
     * Signing Override to be used instead of any signing config provided by Build Type or
     * Product Flavors.
     */
    private final SigningConfig mSigningConfigOverride;

    public static enum Type {
        DEFAULT, LIBRARY, TEST
    }

    /**
     * Parses the manifest file and return the package name.
     * @param manifestFile the manifest file
     * @return the package name found or null
     */
    @Nullable
    public static String getManifestPackage(@NonNull File manifestFile) {
        return new DefaultManifestParser().getPackage(manifestFile);
    }

    /**
     * Creates the configuration with the base source sets.
     *
     * This creates a config with a {@link Type#DEFAULT} type.
     *
     * @param defaultConfig the default configuration. Required.
     * @param defaultSourceProvider the default source provider. Required
     * @param buildType the build type for this variant. Required.
     * @param buildTypeSourceProvider the source provider for the build type. Required.
     */
    public VariantConfiguration(
            @NonNull DefaultProductFlavor defaultConfig,
            @NonNull SourceProvider defaultSourceProvider,
            @NonNull DefaultBuildType buildType,
            @Nullable SourceProvider buildTypeSourceProvider,
            @Nullable SigningConfig signingConfigOverride) {
        this(
                defaultConfig, defaultSourceProvider,
                buildType, buildTypeSourceProvider,
                Type.DEFAULT, null /*testedConfig*/, signingConfigOverride);
    }

    /**
     * Creates the configuration with the base source sets for a given {@link Type}.
     *
     * @param defaultConfig the default configuration. Required.
     * @param defaultSourceProvider the default source provider. Required
     * @param buildType the build type for this variant. Required.
     * @param buildTypeSourceProvider the source provider for the build type.
     * @param type the type of the project.
     * @param signingConfigOverride an optional Signing override to be used for signing.
     */
    public VariantConfiguration(
            @NonNull DefaultProductFlavor defaultConfig,
            @NonNull SourceProvider defaultSourceProvider,
            @NonNull DefaultBuildType buildType,
            @Nullable SourceProvider buildTypeSourceProvider,
            @NonNull Type type,
            @Nullable SigningConfig signingConfigOverride) {
        this(
                defaultConfig, defaultSourceProvider,
                buildType, buildTypeSourceProvider,
                type, null /*testedConfig*/, signingConfigOverride);
    }

    /**
     * Creates the configuration with the base source sets, and an optional tested variant.
     *
     * @param defaultConfig the default configuration. Required.
     * @param defaultSourceProvider the default source provider. Required
     * @param buildType the build type for this variant. Required.
     * @param buildTypeSourceProvider the source provider for the build type.
     * @param type the type of the project.
     * @param testedConfig the reference to the tested project. Required if type is Type.TEST
     * @param signingConfigOverride an optional Signing override to be used for signing.
     */
    public VariantConfiguration(
            @NonNull DefaultProductFlavor defaultConfig,
            @NonNull SourceProvider defaultSourceProvider,
            @NonNull DefaultBuildType buildType,
            @Nullable SourceProvider buildTypeSourceProvider,
            @NonNull Type type,
            @Nullable VariantConfiguration testedConfig,
            @Nullable SigningConfig signingConfigOverride) {
        mDefaultConfig = checkNotNull(defaultConfig);
        mDefaultSourceProvider = checkNotNull(defaultSourceProvider);
        mBuildType = checkNotNull(buildType);
        mBuildTypeSourceProvider = buildTypeSourceProvider;
        mType = checkNotNull(type);
        mTestedConfig = testedConfig;
        mSigningConfigOverride = signingConfigOverride;
        checkState(mType != Type.TEST || mTestedConfig != null);

        mMergedFlavor = mDefaultConfig.clone();
        computeNdkConfig();
    }

    /**
     * Returns the full, unique name of the variant in camel case (starting with a lower case),
     * including BuildType, Flavors and Test (if applicable).
     *
     * @return the name of the variant
     */
    @NonNull
    public String getFullName() {
        if (mFullName == null) {
            StringBuilder sb = new StringBuilder();
            String flavorName = getFlavorName();
            if (!flavorName.isEmpty()) {
                sb.append(flavorName);
                sb.append(StringHelper.capitalize(mBuildType.getName()));
            } else {
                sb.append(mBuildType.getName());
            }

            if (mType == Type.TEST) {
                sb.append("Test");
            }

            mFullName = sb.toString();
        }

        return mFullName;
    }

    /**
     * Returns a full name that includes the given splits name.
     * @param splitName the split name
     * @return a unique name made up of the variant and split names.
     */
    @NonNull
    public String computeFullNameWithSplits(@NonNull String splitName) {
        StringBuilder sb = new StringBuilder();
        String flavorName = getFlavorName();
        if (!flavorName.isEmpty()) {
            sb.append(flavorName);
            sb.append(StringHelper.capitalize(splitName));
        } else {
            sb.append(splitName);
        }

        sb.append(StringHelper.capitalize(mBuildType.getName()));

        if (mType == Type.TEST) {
            sb.append("Test");
        }

        return sb.toString();
    }

    /**
     * Returns the flavor name of the variant, including all flavors in camel case (starting
     * with a lower case). If the variant has no flavor, then an empty string is returned.
     *
     * @return the flavor name or an empty string.
     */
    @NonNull
    public String getFlavorName() {
        if (mFlavorName == null) {
            if (mFlavorConfigs.isEmpty()) {
                mFlavorName = "";
            } else {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (DefaultProductFlavor flavor : mFlavorConfigs) {
                    sb.append(first ? flavor.getName() : StringHelper.capitalize(flavor.getName()));
                    first = false;
                }

                mFlavorName = sb.toString();
            }
        }

        return mFlavorName;
    }

    /**
     * Returns the full, unique name of the variant, including BuildType, flavors and test,
     * dash separated. (similar to full name but with dashes)
     *
     * @return the name of the variant
     */
    @NonNull
    public String getBaseName() {
        if (mBaseName == null) {
            StringBuilder sb = new StringBuilder();

            if (!mFlavorConfigs.isEmpty()) {
                for (ProductFlavor pf : mFlavorConfigs) {
                    sb.append(pf.getName()).append('-');
                }
            }

            sb.append(mBuildType.getName());

            if (mType == Type.TEST) {
                sb.append('-').append("test");
            }

            mBaseName = sb.toString();
        }

        return mBaseName;
    }

    /**
     * Returns a base name that includes the given splits name.
     * @param splitName the split name
     * @return a unique name made up of the variant and split names.
     */
    @NonNull
    public String computeBaseNameWithSplits(@NonNull String splitName) {
        StringBuilder sb = new StringBuilder();

        if (!mFlavorConfigs.isEmpty()) {
            for (ProductFlavor pf : mFlavorConfigs) {
                sb.append(pf.getName()).append('-');
            }
        }

        sb.append(splitName).append('-');
        sb.append(mBuildType.getName());

        if (mType == Type.TEST) {
            sb.append('-').append("test");
        }

        return sb.toString();
    }

    /**
     * Returns a unique directory name (can include multiple folders) for the variant,
     * based on build type, flavor and test.
     * This always uses forward slashes ('/') as separator on all platform.
     *
     * @return the directory name for the variant
     */
    @NonNull
    public String getDirName() {
        if (mDirName == null) {
            StringBuilder sb = new StringBuilder();

            if (mType == Type.TEST) {
                sb.append("test/");
            }

            if (!mFlavorConfigs.isEmpty()) {
                for (DefaultProductFlavor flavor : mFlavorConfigs) {
                    sb.append(flavor.getName());
                }

                sb.append('/').append(mBuildType.getName());

            } else {
                sb.append(mBuildType.getName());
            }

            mDirName = sb.toString();

        }

        return mDirName;
    }

    /**
     * Returns a unique directory name (can include multiple folders) for the variant,
     * based on build type, flavor and test, and splits.
     * This always uses forward slashes ('/') as separator on all platform.
     *
     * @return the directory name for the variant
     */
    @NonNull
    public String computeDirNameWithSplits(@NonNull String... splitNames) {
        StringBuilder sb = new StringBuilder();

        if (mType == Type.TEST) {
            sb.append("test/");
        }

        if (!mFlavorConfigs.isEmpty()) {
            for (DefaultProductFlavor flavor : mFlavorConfigs) {
                sb.append(flavor.getName());
            }

            sb.append('/');
        }

        for (String splitName : splitNames) {
            if (splitName != null) {
                sb.append(splitName).append('/');
            }
        }

        sb.append(mBuildType.getName());

        return sb.toString();
    }

    /**
     * Return the names of the applied flavors.
     *
     * The list contains the dimension names as well.
     *
     * @return the list, possibly empty if there are no flavors.
     */
    @NonNull
    public List<String> getFlavorNamesWithDimensionNames() {
        if (mFlavorConfigs.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> names;
        int count = mFlavorConfigs.size();

        if (count > 1) {
            names = Lists.newArrayListWithCapacity(count * 2);

            for (int i = 0 ; i < count ; i++) {
                names.add(mFlavorConfigs.get(i).getName());
                names.add(mFlavorDimensionNames.get(i));
            }

        } else {
            names = Collections.singletonList(mFlavorConfigs.get(0).getName());
        }

        return names;
    }


    /**
     * Add a new configured ProductFlavor.
     *
     * If multiple flavors are added, the priority follows the order they are added when it
     * comes to resolving Android resources overlays (ie earlier added flavors supersedes
     * latter added ones).
     *
     * @param productFlavor the configured product flavor
     * @param sourceProvider the source provider for the product flavor
     * @param dimensionName the name of the dimension associated with the flavor
     *
     * @return the config object
     */
    @NonNull
    public VariantConfiguration addProductFlavor(
            @NonNull DefaultProductFlavor productFlavor,
            @NonNull SourceProvider sourceProvider,
            @NonNull String dimensionName) {

        mFlavorConfigs.add(productFlavor);
        mFlavorSourceProviders.add(sourceProvider);
        mFlavorDimensionNames.add(dimensionName);

        mMergedFlavor = productFlavor.mergeOver(mMergedFlavor);
        computeNdkConfig();

        return this;
    }

    /**
     * Checks the SourceProviders to ensure that they don't use the same folders.
     *
     * This can't be allowed as it can break incremental support, or
     * generate dup files in resulting APK.
     *
     * @throws java.lang.RuntimeException if a dup is found.
     */
    public void checkSourceProviders() {
        List<SourceProvider> providers = Lists.newArrayListWithExpectedSize(4 + mFlavorSourceProviders.size());

        providers.add(mDefaultSourceProvider);
        if (mBuildTypeSourceProvider != null) {
            providers.add(mBuildTypeSourceProvider);
        }

        providers.addAll(mFlavorSourceProviders);
        if (mVariantSourceProvider != null) {
            providers.add(mVariantSourceProvider);
        }
        if (mMultiFlavorSourceProvider != null) {
            providers.add(mMultiFlavorSourceProvider);
        }

        try {
            checkFileSourceSet(providers, SourceProvider.class.getMethod("getManifestFile"), "manifest");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getJavaDirectories"), "java");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getResourcesDirectories"), "resources");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getAidlDirectories"), "aidl");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getRenderscriptDirectories"), "rs");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getJniDirectories"), "jni");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getResDirectories"), "res");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getAssetsDirectories"), "assets");
            checkFileCollectionSourceSet(providers,
                    SourceProvider.class.getMethod("getJniLibsDirectories"), "jniLibs");

        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException ignored) {
        } catch (IllegalAccessException ignored) {
        }
    }

    private static void checkFileSourceSet(
            List<SourceProvider> providers,
            Method m,
            @NonNull String name)
            throws IllegalAccessException, InvocationTargetException {
        Map<String, File> map = Maps.newHashMap();
        for (SourceProvider sourceProvider : providers) {
            File file = (File) m.invoke(sourceProvider);

            if (!map.isEmpty()) {
                for (Map.Entry<String, File> entry : map.entrySet()) {
                    if (file.equals(entry.getValue())) {
                        throw new RuntimeException(String.format(
                                "SourceSets '%s' and '%s' use the same file for '%s': %s",
                                sourceProvider.getName(),
                                entry.getKey(),
                                name,
                                file));
                    }
                }
            }

            map.put(sourceProvider.getName(), file);
        }
    }

    private static void checkFileCollectionSourceSet(
            List<SourceProvider> providers,
            Method m,
            @NonNull String name)
            throws IllegalAccessException, InvocationTargetException {
        Multimap<String, File> map = ArrayListMultimap.create();
        for (SourceProvider sourceProvider : providers) {
            //noinspection unchecked
            Collection<File> list = (Collection<File>) m.invoke(sourceProvider);

            if (!map.isEmpty()) {
                for (Map.Entry<String, Collection<File>> entry : map.asMap().entrySet()) {
                    Collection<File> existingFiles = entry.getValue();
                    for (File f : list) {
                        if (existingFiles.contains(f)) {
                            throw new RuntimeException(String.format(
                                    "SourceSets '%s' and '%s' use the same file/folder for '%s': %s",
                                    sourceProvider.getName(),
                                    entry.getKey(),
                                    name,
                                    f));
                        }
                    }
                }
            }

            map.putAll(sourceProvider.getName(), list);
        }
    }

    /**
     * Sets the variant-specific source provider.
     * @param sourceProvider the source provider for the product flavor
     *
     * @return the config object
     */
    public VariantConfiguration setVariantSourceProvider(@Nullable SourceProvider sourceProvider) {
        mVariantSourceProvider = sourceProvider;
        return this;
    }

    /**
     * Sets the variant-specific source provider.
     * @param sourceProvider the source provider for the product flavor
     *
     * @return the config object
     */
    public VariantConfiguration setMultiFlavorSourceProvider(@Nullable SourceProvider sourceProvider) {
        mMultiFlavorSourceProvider = sourceProvider;
        return this;
    }

    /**
     * Returns the variant specific source provider
     * @return the source provider or null if none has been provided.
     */
    @Nullable
    public SourceProvider getVariantSourceProvider() {
        return mVariantSourceProvider;
    }

    @Nullable
    public SourceProvider getMultiFlavorSourceProvider() {
        return mMultiFlavorSourceProvider;
    }

    private void computeNdkConfig() {
        mMergedNdkConfig.reset();

        if (mDefaultConfig.getNdkConfig() != null) {
            mMergedNdkConfig.append(mDefaultConfig.getNdkConfig());
        }

        for (int i = mFlavorConfigs.size() - 1 ; i >= 0 ; i--) {
            NdkConfig ndkConfig = mFlavorConfigs.get(i).getNdkConfig();
            if (ndkConfig != null) {
                mMergedNdkConfig.append(ndkConfig);
            }
        }

        if (mBuildType.getNdkConfig() != null && mType != Type.TEST) {
            mMergedNdkConfig.append(mBuildType.getNdkConfig());
        }
    }

    /**
     * Sets the dependencies
     *
     * @param container a DependencyContainer.
     * @return the config object
     */
    @NonNull
    public VariantConfiguration setDependencies(@NonNull DependencyContainer container) {
        // Output of mTestedConfig will not be initialized until the tasks for the tested config are
        // created.  If library output has never been added to mDirectLibraries, checked the output
        // of the mTestedConfig to see if the tasks are now created.
        if (mTestedConfig != null &&
                mTestedConfig.mType == Type.LIBRARY &&
                mTestedConfig.mOutput != null &&
                !mDirectLibraries.contains(mTestedConfig.mOutput)) {
            mDirectLibraries.add(mTestedConfig.mOutput);
        }

        mDirectLibraries.addAll(container.getAndroidDependencies());
        mJars.addAll(container.getJarDependencies());
        mJars.addAll(container.getLocalDependencies());

        resolveIndirectLibraryDependencies(mDirectLibraries, mFlatLibraries);

        for (LibraryDependency libraryDependency : mFlatLibraries) {
            mJars.addAll(libraryDependency.getLocalDependencies());
        }
        return this;
    }

    /**
     * Returns the list of jar dependencies
     * @return a non null collection of Jar dependencies.
     */
    @NonNull
    public Collection<JarDependency> getJars() {
        return mJars;
    }

    /**
     * Sets the output of this variant. This is required when the variant is a library so that
     * the variant that tests this library can properly include the tested library in its own
     * package.
     *
     * @param output the output of the library as an LibraryDependency that will provides the
     *               location of all the created items.
     * @return the config object
     */
    @NonNull
    public VariantConfiguration setOutput(LibraryDependency output) {
        mOutput = output;
        return this;
    }

    @NonNull
    public DefaultProductFlavor getDefaultConfig() {
        return mDefaultConfig;
    }

    @NonNull
    public SourceProvider getDefaultSourceSet() {
        return mDefaultSourceProvider;
    }

    @NonNull
    public DefaultProductFlavor getMergedFlavor() {
        return mMergedFlavor;
    }

    @NonNull
    public DefaultBuildType getBuildType() {
        return mBuildType;
    }

    /**
     * The SourceProvider for the BuildType. Can be null.
     */
    @Nullable
    public SourceProvider getBuildTypeSourceSet() {
        return mBuildTypeSourceProvider;
    }

    public boolean hasFlavors() {
        return !mFlavorConfigs.isEmpty();
    }

    @NonNull
    public List<DefaultProductFlavor> getFlavorConfigs() {
        return mFlavorConfigs;
    }

    /**
     * Returns the list of SourceProviders for the flavors.
     *
     * The list is ordered from higher priority to lower priority.
     *
     * @return the list of Source Providers for the flavors. Never null.
     */
    @NonNull
    public List<SourceProvider> getFlavorSourceProviders() {
        return mFlavorSourceProviders;
    }

    public boolean hasLibraries() {
        return !mDirectLibraries.isEmpty();
    }

    /**
     * Returns the direct library dependencies
     */
    @NonNull
    public List<LibraryDependency> getDirectLibraries() {
        return mDirectLibraries;
    }

    /**
     * Returns all the library dependencies, direct and transitive.
     */
    @NonNull
    public List<LibraryDependency> getAllLibraries() {
        return mFlatLibraries;
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    @Nullable
    public VariantConfiguration getTestedConfig() {
        return mTestedConfig;
    }

    /**
     * Resolves a given list of libraries, finds out if they depend on other libraries, and
     * returns a flat list of all the direct and indirect dependencies in the proper order (first
     * is higher priority when calling aapt).
     * @param directDependencies the libraries to resolve
     * @param outFlatDependencies where to store all the libraries.
     */
    @VisibleForTesting
    void resolveIndirectLibraryDependencies(List<LibraryDependency> directDependencies,
                                            List<LibraryDependency> outFlatDependencies) {
        if (directDependencies == null) {
            return;
        }
        // loop in the inverse order to resolve dependencies on the libraries, so that if a library
        // is required by two higher level libraries it can be inserted in the correct place
        for (int i = directDependencies.size() - 1  ; i >= 0 ; i--) {
            LibraryDependency library = directDependencies.get(i);

            // get its libraries
            Collection<LibraryDependency> dependencies = library.getDependencies();
            List<LibraryDependency> depList = Lists.newArrayList(dependencies);

            // resolve the dependencies for those libraries
            resolveIndirectLibraryDependencies(depList, outFlatDependencies);

            // and add the current one (if needed) in front (higher priority)
            if (!outFlatDependencies.contains(library)) {
                outFlatDependencies.add(0, library);
            }
        }
    }

    /**
     * Returns the original application ID before any overrides from flavors.
     * If the variant is a test variant, then the application ID is the one coming from the
     * configuration of the tested variant, and this call is similar to {@link #getApplicationId()}
     * @return the original application ID
     */
    @Nullable
    public String getOriginalApplicationId() {
        if (mType == VariantConfiguration.Type.TEST) {
            return getApplicationId();
        }

        return getPackageFromManifest();
    }

    /**
     * Returns the application ID for this variant. This could be coming from the manifest or
     * could be overridden through the product flavors and/or the build type.
     * @return the application ID
     */
    @NonNull
    public String getApplicationId() {
        String id;

        if (mType == Type.TEST) {
            assert mTestedConfig != null;

            id = mMergedFlavor.getTestApplicationId();
            if (id == null) {
                String testedPackage = mTestedConfig.getApplicationId();
                id = testedPackage + ".test";
            }
        } else {
            // first get package override.
            id = getIdOverride();
            // if it's null, this means we just need the default package
            // from the manifest since both flavor and build type do nothing.
            if (id == null) {
                id = getPackageFromManifest();
            }
        }

        if (id == null) {
            throw new RuntimeException("Failed to get application id for " + getFullName());
        }

        return id;
    }

    @Nullable
    public String getTestedApplicationId() {
        if (mType == Type.TEST) {
            assert mTestedConfig != null;
            if (mTestedConfig.mType == Type.LIBRARY) {
                return getApplicationId();
            } else {
                return mTestedConfig.getApplicationId();
            }
        }

        return null;
    }

    /**
     * Returns the application id override value coming from the Product Flavor and/or the
     * Build Type. If the package/id is not overridden then this returns null.
     *
     * @return the id override or null
     */
    @Nullable
    public String getIdOverride() {
        String idName = mMergedFlavor.getApplicationId();
        String idSuffix = mBuildType.getApplicationIdSuffix();

        if (idSuffix != null && !idSuffix.isEmpty()) {
            if (idName == null) {
                idName = getPackageFromManifest();
            }

            if (idSuffix.charAt(0) == '.') {
                idName = idName + idSuffix;
            } else {
                idName = idName + '.' + idSuffix;
            }
        }

        return idName;
    }

    /**
     * Returns the version name for this variant. This could be coming from the manifest or
     * could be overridden through the product flavors, and can have a suffix specified by
     * the build type.
     *
     * @return the version name
     */
    @Nullable
    public String getVersionName() {
        String versionName = mMergedFlavor.getVersionName();
        String versionSuffix = mBuildType.getVersionNameSuffix();

        if (versionSuffix != null && !versionSuffix.isEmpty()) {
            if (versionName == null) {
                if (mType != Type.TEST) {
                    versionName = getVersionNameFromManifest();
                } else {
                    versionName = "";
                }
            }

            versionName = versionName + versionSuffix;
        }

        return versionName;
    }

    /**
     * Returns the version code for this variant. This could be coming from the manifest or
     * could be overridden through the product flavors, and can have a suffix specified by
     * the build type.
     *
     * @return the version code or -1 if there was non defined.
     */
    public int getVersionCode() {
        int versionCode = mMergedFlavor.getVersionCode();

        if (versionCode == -1 && mType != Type.TEST) {

            versionCode = getVersionCodeFromManifest();
        }

        return versionCode;
    }

    private static final String DEFAULT_TEST_RUNNER = "android.test.InstrumentationTestRunner";
    private static final Boolean DEFAULT_HANDLE_PROFILING = false;
    private static final Boolean DEFAULT_FUNCTIONAL_TEST = false;

    /**
     * Returns the instrumentationRunner to use to test this variant, or if the
     * variant is a test, the one to use to test the tested variant.
     * @return the instrumentation test runner name
     */
    @NonNull
    public String getInstrumentationRunner() {
        VariantConfiguration config = this;
        if (mType == Type.TEST) {
            config = getTestedConfig();
        }
        String runner = config.mMergedFlavor.getTestInstrumentationRunner();
        return runner != null ? runner : DEFAULT_TEST_RUNNER;
    }

    /**
     * Returns handleProfiling value to use to test this variant, or if the
     * variant is a test, the one to use to test the tested variant.
     * @return the handleProfiling value
     */
    @NonNull
    public Boolean getHandleProfiling() {
        VariantConfiguration config = this;
        if (mType == Type.TEST) {
            config = getTestedConfig();
        }
        Boolean handleProfiling = config.mMergedFlavor.getTestHandleProfiling();
        return handleProfiling != null ? handleProfiling : DEFAULT_HANDLE_PROFILING;
    }

    /**
     * Returns functionalTest value to use to test this variant, or if the
     * variant is a test, the one to use to test the tested variant.
     * @return the functionalTest value
     */
    @NonNull
    public Boolean getFunctionalTest() {
        VariantConfiguration config = this;
        if (mType == Type.TEST) {
            config = getTestedConfig();
        }
        Boolean functionalTest = config.mMergedFlavor.getTestFunctionalTest();
        return functionalTest != null ? functionalTest : DEFAULT_FUNCTIONAL_TEST;
    }

    /**
     * Reads the package name from the manifest. This is unmodified by the build type.
     */
    @Nullable
    public String getPackageFromManifest() {
        assert mType != Type.TEST;
        File manifestLocation = mDefaultSourceProvider.getManifestFile();
        return sManifestParser.getPackage(manifestLocation);
    }

    /**
     * Reads the version name from the manifest.
     */
    @Nullable
    public String getVersionNameFromManifest() {
        File manifestLocation = mDefaultSourceProvider.getManifestFile();
        return sManifestParser.getVersionName(manifestLocation);
    }

    /**
     * Reads the version code from the manifest.
     */
    public int getVersionCodeFromManifest() {
        File manifestLocation = mDefaultSourceProvider.getManifestFile();
        return sManifestParser.getVersionCode(manifestLocation);
    }

    /**
     * Return the minSdkVersion for this variant.
     *
     * This uses both the value from the manifest (if present), and the override coming
     * from the flavor(s) (if present).
     * @return the minSdkVersion
     */
    @NonNull
    public ApiVersion getMinSdkVersion() {
        if (mTestedConfig != null) {
            return mTestedConfig.getMinSdkVersion();
        }
        ApiVersion minSdkVersion = mMergedFlavor.getMinSdkVersion();
        if (minSdkVersion == null) {
            // read it from the main manifest
            File manifestLocation = mDefaultSourceProvider.getManifestFile();
            minSdkVersion = DefaultApiVersion.create(
                    sManifestParser.getMinSdkVersion(manifestLocation));
        }

        return minSdkVersion;
    }

    /**
     * Return the targetSdkVersion for this variant.
     *
     * This uses both the value from the manifest (if present), and the override coming
     * from the flavor(s) (if present).
     * @return the targetSdkVersion
     */
    @NonNull
    public ApiVersion getTargetSdkVersion() {
        if (mTestedConfig != null) {
            return mTestedConfig.getTargetSdkVersion();
        }
        ApiVersion targetSdkVersion = mMergedFlavor.getTargetSdkVersion();
        if (targetSdkVersion == null) {
            // read it from the main manifest
            File manifestLocation = mDefaultSourceProvider.getManifestFile();
            targetSdkVersion = DefaultApiVersion.create(
                    sManifestParser.getTargetSdkVersion(manifestLocation));
        }

        return targetSdkVersion;
    }

    @Nullable
    public File getMainManifest() {
        File defaultManifest = mDefaultSourceProvider.getManifestFile();

        // this could not exist in a test project.
        if (defaultManifest.isFile()) {
            return defaultManifest;
        }

        return null;
    }

    /**
     * Returns a list of sorted SourceProvider in order of ascending order, meaning, the earlier
     * items are meant to be overridden by later items.
     *
     * @return a list of source provider
     */
    @NonNull
    public List<SourceProvider> getSortedSourceProviders() {
        List<SourceProvider> providers = Lists.newArrayList();

        // first the default source provider
        providers.add(mDefaultSourceProvider);

        // the list of flavor must be reversed to use the right overlay order.
        for (int n = mFlavorSourceProviders.size() - 1; n >= 0 ; n--) {
            providers.add(mFlavorSourceProviders.get(n));
        }

        // multiflavor specific overrides flavor
        if (mMultiFlavorSourceProvider != null) {
            providers.add(mMultiFlavorSourceProvider);
        }

        // build type overrides flavors
        if (mType != Type.TEST && mBuildTypeSourceProvider != null) {
            providers.add(mBuildTypeSourceProvider);
        }

        // variant specific overrides all
        if (mVariantSourceProvider != null) {
            providers.add(mVariantSourceProvider);
        }

        return providers;
    }

    @NonNull
    public List<BaseConfig> getSortedBaseConfigs() {
        List<BaseConfig> configs = Lists.newArrayList();

        configs.add(mDefaultConfig);

        // the list of flavor must be reversed to use the right overlay order.
        for (int n = mFlavorConfigs.size() - 1; n >= 0 ; n--) {
            configs.add(mFlavorConfigs.get(n));
        }

        // build type overrides flavors
        if (mType != Type.TEST) {
            configs.add(mBuildType);
        }

        return configs;
    }

    @NonNull
    public List<File> getManifestOverlays() {
        List<File> inputs = Lists.newArrayList();

        if (mVariantSourceProvider != null) {
            File variantLocation = mVariantSourceProvider.getManifestFile();
            if (variantLocation.isFile()) {
                inputs.add(variantLocation);
            }
        }

        if (mBuildTypeSourceProvider != null) {
            File typeLocation = mBuildTypeSourceProvider.getManifestFile();
            if (typeLocation.isFile()) {
                inputs.add(typeLocation);
            }
        }

        if (mMultiFlavorSourceProvider != null) {
            File variantLocation = mMultiFlavorSourceProvider.getManifestFile();
            if (variantLocation.isFile()) {
                inputs.add(variantLocation);
            }
        }

        for (SourceProvider sourceProvider : mFlavorSourceProviders) {
            File f = sourceProvider.getManifestFile();
            if (f.isFile()) {
                inputs.add(f);
            }
        }

        return inputs;
    }

    /**
     * Returns the dynamic list of {@link ResourceSet} based on the configuration, its dependencies,
     * as well as tested config if applicable (test of a library).
     *
     * The list is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on. This is meant to facilitate usage of the list in a
     * {@link com.android.ide.common.res2.ResourceMerger}.
     *
     * @param generatedResFolders a list of generated res folders
     * @param includeDependencies whether to include in the result the resources of the dependencies
     *
     * @return a list ResourceSet.
     */
    @NonNull
    public List<ResourceSet> getResourceSets(@NonNull List<File> generatedResFolders,
                                             boolean includeDependencies) {
        List<ResourceSet> resourceSets = Lists.newArrayList();

        // the list of dependency must be reversed to use the right overlay order.
        if (includeDependencies) {
            for (int n = mFlatLibraries.size() - 1 ; n >= 0 ; n--) {
                LibraryDependency dependency = mFlatLibraries.get(n);
                File resFolder = dependency.getResFolder();
                if (resFolder.isDirectory()) {
                    ResourceSet resourceSet = new ResourceSet(dependency.getFolder().getName());
                    resourceSet.addSource(resFolder);
                    resourceSets.add(resourceSet);
                }
            }
        }

        Collection<File> mainResDirs = mDefaultSourceProvider.getResDirectories();

        // the main + generated res folders are in the same ResourceSet
        ResourceSet resourceSet = new ResourceSet(BuilderConstants.MAIN);
        resourceSet.addSources(mainResDirs);
        if (!generatedResFolders.isEmpty()) {
            for (File generatedResFolder : generatedResFolders) {
                resourceSet.addSource(generatedResFolder);

            }
        }
        resourceSets.add(resourceSet);

        // the list of flavor must be reversed to use the right overlay order.
        for (int n = mFlavorSourceProviders.size() - 1; n >= 0 ; n--) {
            SourceProvider sourceProvider = mFlavorSourceProviders.get(n);

            Collection<File> flavorResDirs = sourceProvider.getResDirectories();
            // we need the same of the flavor config, but it's in a different list.
            // This is fine as both list are parallel collections with the same number of items.
            resourceSet = new ResourceSet(sourceProvider.getName());
            resourceSet.addSources(flavorResDirs);
            resourceSets.add(resourceSet);
        }

        // multiflavor specific overrides flavor
        if (mMultiFlavorSourceProvider != null) {
            Collection<File> variantResDirs = mMultiFlavorSourceProvider.getResDirectories();
            resourceSet = new ResourceSet(getFlavorName());
            resourceSet.addSources(variantResDirs);
            resourceSets.add(resourceSet);
        }

        // build type overrides the flavors
        if (mBuildTypeSourceProvider != null) {
            Collection<File> typeResDirs = mBuildTypeSourceProvider.getResDirectories();
            resourceSet = new ResourceSet(mBuildType.getName());
            resourceSet.addSources(typeResDirs);
            resourceSets.add(resourceSet);
        }

        // variant specific overrides all
        if (mVariantSourceProvider != null) {
            Collection<File> variantResDirs = mVariantSourceProvider.getResDirectories();
            resourceSet = new ResourceSet(getFullName());
            resourceSet.addSources(variantResDirs);
            resourceSets.add(resourceSet);
        }

        return resourceSets;
    }

    /**
     * Returns the dynamic list of {@link AssetSet} based on the configuration, its dependencies,
     * as well as tested config if applicable (test of a library).
     *
     * The list is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on. This is meant to facilitate usage of the list in a
     * {@link com.android.ide.common.res2.AssetMerger}.
     *
     * @return a list ResourceSet.
     */
    @NonNull
    public List<AssetSet> getAssetSets(@NonNull List<File> generatedResFolders,
                                       boolean includeDependencies) {
        List<AssetSet> assetSets = Lists.newArrayList();

        if (includeDependencies) {
            // the list of dependency must be reversed to use the right overlay order.
            for (int n = mFlatLibraries.size() - 1 ; n >= 0 ; n--) {
                LibraryDependency dependency = mFlatLibraries.get(n);
                File assetFolder = dependency.getAssetsFolder();
                if (assetFolder.isDirectory()) {
                    AssetSet assetSet = new AssetSet(dependency.getFolder().getName());
                    assetSet.addSource(assetFolder);
                    assetSets.add(assetSet);
                }
            }
        }

        Collection<File> mainResDirs = mDefaultSourceProvider.getAssetsDirectories();

        // the main + generated asset folders are in the same AssetSet
        AssetSet assetSet = new AssetSet(BuilderConstants.MAIN);
        assetSet.addSources(mainResDirs);
        if (!generatedResFolders.isEmpty()) {
            for (File generatedResFolder : generatedResFolders) {
                assetSet.addSource(generatedResFolder);
            }
        }
        assetSets.add(assetSet);

        // the list of flavor must be reversed to use the right overlay order.
        for (int n = mFlavorSourceProviders.size() - 1; n >= 0 ; n--) {
            SourceProvider sourceProvider = mFlavorSourceProviders.get(n);

            Collection<File> flavorResDirs = sourceProvider.getAssetsDirectories();
            // we need the same of the flavor config, but it's in a different list.
            // This is fine as both list are parallel collections with the same number of items.
            assetSet = new AssetSet(mFlavorConfigs.get(n).getName());
            assetSet.addSources(flavorResDirs);
            assetSets.add(assetSet);
        }

        // multiflavor specific overrides flavor
        if (mMultiFlavorSourceProvider != null) {
            Collection<File> variantResDirs = mMultiFlavorSourceProvider.getAssetsDirectories();
            assetSet = new AssetSet(getFlavorName());
            assetSet.addSources(variantResDirs);
            assetSets.add(assetSet);
        }

        // build type overrides flavors
        if (mBuildTypeSourceProvider != null) {
            Collection<File> typeResDirs = mBuildTypeSourceProvider.getAssetsDirectories();
            assetSet = new AssetSet(mBuildType.getName());
            assetSet.addSources(typeResDirs);
            assetSets.add(assetSet);
        }

        // variant specific overrides all
        if (mVariantSourceProvider != null) {
            Collection<File> variantResDirs = mVariantSourceProvider.getAssetsDirectories();
            assetSet = new AssetSet(getFullName());
            assetSet.addSources(variantResDirs);
            assetSets.add(assetSet);
        }

        return assetSets;
    }

    @NonNull
    public List<File> getLibraryJniFolders() {
        List<File> list = Lists.newArrayListWithExpectedSize(mFlatLibraries.size());

        for (int n = mFlatLibraries.size() - 1 ; n >= 0 ; n--) {
            LibraryDependency dependency = mFlatLibraries.get(n);
            File jniFolder = dependency.getJniFolder();
            if (jniFolder.isDirectory()) {
                list.add(jniFolder);
            }
        }

        return list;
    }

    /**
     * Returns all the renderscript import folder that are outside of the current project.
     */
    @NonNull
    public List<File> getRenderscriptImports() {
        List<File> list = Lists.newArrayList();

        for (LibraryDependency lib : mFlatLibraries) {
            File rsLib = lib.getRenderscriptFolder();
            if (rsLib.isDirectory()) {
                list.add(rsLib);
            }
        }

        return list;
    }

    /**
     * Returns all the renderscript source folder from the main config, the flavors and the
     * build type.
     *
     * @return a list of folders.
     */
    @NonNull
    public List<File> getRenderscriptSourceList() {
        List<SourceProvider> providers = getSortedSourceProviders();

        List<File> sourceList = Lists.newArrayListWithExpectedSize(providers.size());

        for (SourceProvider provider : providers) {
            sourceList.addAll(provider.getRenderscriptDirectories());
        }

        return sourceList;
    }

    /**
     * Returns all the aidl import folder that are outside of the current project.
     */
    @NonNull
    public List<File> getAidlImports() {
        List<File> list = Lists.newArrayList();

        for (LibraryDependency lib : mFlatLibraries) {
            File aidlLib = lib.getAidlFolder();
            if (aidlLib.isDirectory()) {
                list.add(aidlLib);
            }
        }

        return list;
    }

    @NonNull
    public List<File> getAidlSourceList() {
        List<SourceProvider> providers = getSortedSourceProviders();

        List<File> sourceList = Lists.newArrayListWithExpectedSize(providers.size());

        for (SourceProvider provider : providers) {
            sourceList.addAll(provider.getAidlDirectories());
        }

        return sourceList;
    }

    @NonNull
    public List<File> getJniSourceList() {
        List<SourceProvider> providers = getSortedSourceProviders();

        List<File> sourceList = Lists.newArrayListWithExpectedSize(providers.size());

        for (SourceProvider provider : providers) {
            sourceList.addAll(provider.getJniDirectories());
        }

        return sourceList;
    }

    @NonNull
    public List<File> getJniLibsList() {
        List<SourceProvider> providers = getSortedSourceProviders();

        List<File> sourceList = Lists.newArrayListWithExpectedSize(providers.size());

        for (SourceProvider provider : providers) {
            sourceList.addAll(provider.getJniLibsDirectories());
        }

        return sourceList;
    }

    /**
     * Returns the compile classpath for this config. If the config tests a library, this
     * will include the classpath of the tested config
     *
     * @return a non null, but possibly empty set.
     */
    @NonNull
    public Set<File> getCompileClasspath() {
        Set<File> classpath = Sets.newHashSetWithExpectedSize(mJars.size() + mFlatLibraries.size());

        for (LibraryDependency lib : mFlatLibraries) {
            classpath.add(lib.getJarFile());
            for (File jarFile : lib.getLocalJars()) {
                classpath.add(jarFile);
            }
        }

        for (JarDependency jar : mJars) {
            if (jar.isCompiled()) {
                classpath.add(jar.getJarFile());
            }
        }

        return classpath;
    }

    /**
     * Returns the list of packaged jars for this config. If the config tests a library, this
     * will include the jars of the tested config
     *
     * @return a non null, but possibly empty list.
     */
    @NonNull
    public Set<File> getPackagedJars() {
        Set<File> jars = Sets.newHashSetWithExpectedSize(mJars.size() + mFlatLibraries.size());

        for (JarDependency jar : mJars) {
            File jarFile = jar.getJarFile();
            if (jar.isPackaged() && jarFile.exists()) {
                jars.add(jarFile);
            }
        }

        for (LibraryDependency libraryDependency : mFlatLibraries) {
            File libJar = libraryDependency.getJarFile();
            if (libJar.exists()) {
                jars.add(libJar);
            }
            for (File jarFile : libraryDependency.getLocalJars()) {
                if (jarFile.isFile()) {
                    jars.add(jarFile);
                }
            }
        }

        return jars;
    }

    /**
     * Returns the list of provided-only jars for this config.
     *
     * @return a non null, but possibly empty list.
     */
    @NonNull
    public List<File> getProvidedOnlyJars() {
        Set<File> jars = Sets.newHashSetWithExpectedSize(mJars.size());

        for (JarDependency jar : mJars) {
            File jarFile = jar.getJarFile();
            if (jar.isCompiled() && !jar.isPackaged() && jarFile.exists()) {
                jars.add(jarFile);
            }
        }

        return Lists.newArrayList(jars);
    }

    /**
     * Returns a list of items for the BuildConfig class.
     *
     * Items can be either fields (instance of {@link com.android.builder.model.ClassField})
     * or comments (instance of String).
     *
     * @return a list of items.
     */
    @NonNull
    public List<Object> getBuildConfigItems() {
        List<Object> fullList = Lists.newArrayList();

        Set<String> usedFieldNames = Sets.newHashSet();

        Collection<ClassField> list = mBuildType.getBuildConfigFields().values();
        if (!list.isEmpty()) {
            fullList.add("Fields from build type: " + mBuildType.getName());
            for (ClassField f : list) {
                usedFieldNames.add(f.getName());
                fullList.add(f);
            }
        }

        for (DefaultProductFlavor flavor : mFlavorConfigs) {
            list = flavor.getBuildConfigFields().values();
            if (!list.isEmpty()) {
                fullList.add("Fields from product flavor: " + flavor.getName());
                for (ClassField f : list) {
                    String name = f.getName();
                    if (!usedFieldNames.contains(name)) {
                        usedFieldNames.add(f.getName());
                        fullList.add(f);
                    }
                }
            }
        }

        list = mDefaultConfig.getBuildConfigFields().values();
        if (!list.isEmpty()) {
            fullList.add("Fields from default config.");
            for (ClassField f : list) {
                String name = f.getName();
                if (!usedFieldNames.contains(name)) {
                    usedFieldNames.add(f.getName());
                    fullList.add(f);
                }
            }
        }

        return fullList;
    }

    /**
     * Returns a list of generated resource values.
     *
     * Items can be either fields (instance of {@link com.android.builder.model.ClassField})
     * or comments (instance of String).
     *
     * @return a list of items.
     */
    @NonNull
    public List<Object> getResValues() {
        List<Object> fullList = Lists.newArrayList();

        Set<String> usedFieldNames = Sets.newHashSet();

        Collection<ClassField> list = mBuildType.getResValues().values();
        if (!list.isEmpty()) {
            fullList.add("Values from build type: " + mBuildType.getName());
            for (ClassField f : list) {
                usedFieldNames.add(f.getName());
                fullList.add(f);
            }
        }

        for (DefaultProductFlavor flavor : mFlavorConfigs) {
            list = flavor.getResValues().values();
            if (!list.isEmpty()) {
                fullList.add("Values from product flavor: " + flavor.getName());
                for (ClassField f : list) {
                    String name = f.getName();
                    if (!usedFieldNames.contains(name)) {
                        usedFieldNames.add(f.getName());
                        fullList.add(f);
                    }
                }
            }
        }

        list = mDefaultConfig.getResValues().values();
        if (!list.isEmpty()) {
            fullList.add("Values from default config.");
            for (ClassField f : list) {
                String name = f.getName();
                if (!usedFieldNames.contains(name)) {
                    usedFieldNames.add(f.getName());
                    fullList.add(f);
                }
            }
        }

        return fullList;
    }

    @Nullable
    public SigningConfig getSigningConfig() {
        if (mSigningConfigOverride != null) {
            return mSigningConfigOverride;
        }

        SigningConfig signingConfig = mBuildType.getSigningConfig();
        if (signingConfig != null) {
            return signingConfig;
        }
        return mMergedFlavor.getSigningConfig();
    }

    public boolean isSigningReady() {
        SigningConfig signingConfig = getSigningConfig();
        return signingConfig != null && signingConfig.isSigningReady();
    }

    /**
     * Returns the proguard config files coming from the project but also from the dependencies.
     *
     * Note that if the method is set to include config files coming from libraries, they will
     * only be included if the aars have already been unzipped.
     *
     * @param includeLibraries whether to include the library dependencies.
     * @return a non null list of proguard files.
     */
    @NonNull
    public List<File> getProguardFiles(boolean includeLibraries) {
        List<File> fullList = Lists.newArrayList();

        // add the config files from the build type, main config and flavors
        fullList.addAll(mDefaultConfig.getProguardFiles());
        fullList.addAll(mBuildType.getProguardFiles());

        for (DefaultProductFlavor flavor : mFlavorConfigs) {
            fullList.addAll(flavor.getProguardFiles());
        }

        // now add the one coming from the library dependencies
        if (includeLibraries) {
            for (LibraryDependency libraryDependency : mFlatLibraries) {
                File proguardRules = libraryDependency.getProguardRules();
                if (proguardRules.exists()) {
                    fullList.add(proguardRules);
                }
            }
        }

        return fullList;
    }

    @NonNull
    public List<Object> getConsumerProguardFiles() {
        List<Object> fullList = Lists.newArrayList();

        // add the config files from the build type, main config and flavors
        fullList.addAll(mDefaultConfig.getConsumerProguardFiles());
        fullList.addAll(mBuildType.getConsumerProguardFiles());

        for (DefaultProductFlavor flavor : mFlavorConfigs) {
            fullList.addAll(flavor.getConsumerProguardFiles());
        }

        return fullList;
    }

    @NonNull
    public NdkConfig getNdkConfig() {
        return mMergedNdkConfig;
    }

    @Nullable
    public Set<String> getSupportedAbis() {
        if (mMergedNdkConfig != null) {
            return mMergedNdkConfig.getAbiFilters();
        }

        return null;
    }

    public boolean isTestCoverageEnabled() {
        return mBuildType.isTestCoverageEnabled();
    }

    /**
     * Returns the merged manifest placeholders. All product flavors are merged first, then build
     * type specific placeholders are added and potentially overrides product flavors values.
     * @return the merged manifest placeholders for a build variant.
     */
    @NonNull
    public Map<String, String> getManifestPlaceholders() {
        Map<String, String> mergedFlavorsPlaceholders = mMergedFlavor.getManifestPlaceholders();
        // so far, blindly override the build type placeholders
        mergedFlavorsPlaceholders.putAll(mBuildType.getManifestPlaceholders());
        return mergedFlavorsPlaceholders;
    }
}

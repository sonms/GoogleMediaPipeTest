Pod::Spec.new do |spec|
    spec.name                     = 'landmarkkit'
    spec.version                  = '0.1.0'
    spec.homepage                 = 'https://example.invalid/landmarkkit'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = 'Face/Hand landmark detection core for Android and iOS'
    spec.vendored_frameworks      = 'build/cocoapods/framework/LandmarkKit.framework'
    spec.libraries                = 'c++'
    spec.ios.deployment_target    = '17.0'
    spec.dependency 'MediaPipeTasksVision', '0.10.21'
    if !Dir.exist?('build/cocoapods/framework/LandmarkKit.framework') || Dir.empty?('build/cocoapods/framework/LandmarkKit.framework')
        raise "
        Kotlin framework 'LandmarkKit' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:
            ./gradlew :landmarkkit:generateDummyFramework
        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':landmarkkit',
        'PRODUCT_MODULE_NAME' => 'LandmarkKit',
    }
    spec.script_phases = [
        {
            :name => 'Build landmarkkit',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                    echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                    exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
end

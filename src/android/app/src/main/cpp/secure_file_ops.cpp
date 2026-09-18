#include <jni.h>

#include <cerrno>
#include <fcntl.h>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

namespace {

bool isSafeComponent(const std::string& component) {
    return !component.empty() && component != "." && component != ".." &&
           component.find('/') == std::string::npos &&
           component.find('\\') == std::string::npos &&
           component.find('\0') == std::string::npos;
}

int openDirectory(const std::string& path) {
    if (path.empty() || path[0] != '/') return -EINVAL;
    const int directory = open(
        path.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW
    );
    if (directory < 0) return -errno;
    struct stat info = {};
    if (fstat(directory, &info) < 0) {
        const int error = errno == 0 ? EIO : errno;
        close(directory);
        return -error;
    }
    if (!S_ISDIR(info.st_mode)) {
        close(directory);
        return -ENOTDIR;
    }
    return directory;
}

std::vector<std::string> readComponents(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> components;
    if (values == nullptr) return components;
    const jsize count = env->GetArrayLength(values);
    components.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        if (value == nullptr) return {};
        const char* utf = env->GetStringUTFChars(value, nullptr);
        if (utf == nullptr) {
            env->DeleteLocalRef(value);
            return {};
        }
        components.emplace_back(utf);
        env->ReleaseStringUTFChars(value, utf);
        env->DeleteLocalRef(value);
        if (!isSafeComponent(components.back())) return {};
    }
    return components;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_openminis_app_runtime_files_SecureFileOps_ensureDirectoriesNative(
    JNIEnv* env,
    jclass,
    jstring root,
    jobjectArray components,
    jint mode
) {
    if (root == nullptr || mode < 0) return -EINVAL;
    const char* rootUtf = env->GetStringUTFChars(root, nullptr);
    if (rootUtf == nullptr) return -EINVAL;
    const std::string rootPath(rootUtf);
    env->ReleaseStringUTFChars(root, rootUtf);

    const std::vector<std::string> names = readComponents(env, components);
    if (components != nullptr && env->GetArrayLength(components) != static_cast<jsize>(names.size())) {
        return -EINVAL;
    }

    int current = openDirectory(rootPath);
    if (current < 0) return current;
    for (const std::string& name : names) {
        int next = openat(
            current,
            name.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW
        );
        if (next < 0 && errno == ENOENT) {
            if (mkdirat(current, name.c_str(), static_cast<mode_t>(mode)) < 0 && errno != EEXIST) {
                const int error = errno;
                close(current);
                return -error;
            }
            next = openat(
                current,
                name.c_str(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW
            );
        }
        if (next < 0) {
            const int error = errno;
            close(current);
            return -error;
        }
        close(current);
        current = next;
    }
    close(current);
    return 0;
}

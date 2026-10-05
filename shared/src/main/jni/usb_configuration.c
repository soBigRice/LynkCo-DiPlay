#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

// AOSP libusbhost uses this ioctl too. Preserve errno immediately; do not retry a mutation.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_setConfiguration(
        JNIEnv *env, jobject self, jint fd, jint configuration_id) {
    (void) env;
    (void) self;
    if (fd < 0) return EBADF;
    if (configuration_id < 1 || configuration_id > 255) return EINVAL;
    unsigned int configuration = (unsigned int) configuration_id;
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &configuration) == 0) return 0;
    return errno;
}

// Conditional, atomic handoff: refuses usbfs and every driver except the two approved classes.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_detachAndClaim(
        JNIEnv *env, jobject self, jint fd, jint interface_id, jstring expected_driver) {
    (void) self;
    if (fd < 0) return EBADF;
    if (interface_id < 0 || interface_id > 255 || !expected_driver) return EINVAL;
    const char *expected = (*env)->GetStringUTFChars(env, expected_driver, NULL);
    if (!expected) return ENOMEM;
    struct usbdevfs_disconnect_claim request;
    memset(&request, 0, sizeof(request));
    int allowed = strcmp(expected, "snd-usb-audio") == 0 || strcmp(expected, "usbhid") == 0;
    if (allowed) snprintf(request.driver, sizeof(request.driver), "%s", expected);
    (*env)->ReleaseStringUTFChars(env, expected_driver, expected);
    if (!allowed) return EINVAL;
    request.interface = (unsigned int) interface_id;
    request.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &request) == 0) return 0;
    return errno; // No unconditional DISCONNECT fallback on old or restricted OEM kernels.
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_release(
        JNIEnv *env, jobject self, jint fd, jint interface_id) {
    (void) env; (void) self;
    if (fd < 0) return EBADF;
    if (interface_id < 0 || interface_id > 255) return EINVAL;
    unsigned int number = (unsigned int) interface_id;
    if (ioctl(fd, USBDEVFS_RELEASEINTERFACE, &number) == 0) return 0;
    return errno;
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_reconnect(
        JNIEnv *env, jobject self, jint fd, jint interface_id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (interface_id < 0 || interface_id > 255) return -EINVAL;
    struct usbdevfs_ioctl request;
    memset(&request, 0, sizeof(request));
    request.ifno = interface_id;
    request.ioctl_code = USBDEVFS_CONNECT;
    int result = ioctl(fd, USBDEVFS_IOCTL, &request);
    return result < 0 ? -errno : result;
}

// GETDRIVER is read-only. "usbfs" does not identify which process owns an interface.
JNIEXPORT jstring JNICALL Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_driver(
        JNIEnv *env, jobject self, jint fd, jint interface_id) {
    (void) self;
    struct usbdevfs_getdriver request;
    memset(&request, 0, sizeof(request));
    int error = 0;
    if (fd < 0) error = EBADF;
    else if (interface_id < 0 || interface_id > 255) error = EINVAL;
    else {
        request.interface = (unsigned int) interface_id;
        if (ioctl(fd, USBDEVFS_GETDRIVER, &request) < 0) error = errno;
    }
    if (error != 0) {
        char result[32];
        snprintf(result, sizeof(result), "errno:%d", error);
        return (*env)->NewStringUTF(env, result);
    }
    request.driver[sizeof(request.driver) - 1] = '\0';
    return (*env)->NewStringUTF(env, request.driver);
}

// libunmuffle_opencl.so: the OpenCL functions ggml's OpenCL backend calls,
// forwarded to the phone's driver. Apps can load the vendor's libOpenCL.so
// where the vendor makes it public (Samsung); Pixels expose it only as
// libOpenCL-pixel.so. Without either, clGetPlatformIDs finds no platform and
// ggml stays on the CPU.
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <android/log.h>

#define CL_FUNCTIONS(X) \
    X(clBuildProgram) X(clCreateBuffer) X(clCreateBufferWithProperties) \
    X(clCreateCommandQueue) X(clCreateContext) X(clCreateImage) X(clCreateKernel) \
    X(clCreateProgramWithBinary) X(clCreateProgramWithSource) X(clCreateSubBuffer) \
    X(clEnqueueBarrierWithWaitList) X(clEnqueueCopyBuffer) X(clEnqueueFillBuffer) \
    X(clEnqueueMapBuffer) X(clEnqueueMarkerWithWaitList) X(clEnqueueNDRangeKernel) \
    X(clEnqueueReadBuffer) X(clEnqueueUnmapMemObject) X(clEnqueueWriteBuffer) \
    X(clFinish) X(clFlush) X(clGetDeviceIDs) X(clGetDeviceInfo) \
    X(clGetEventProfilingInfo) X(clGetKernelInfo) X(clGetKernelSubGroupInfo) \
    X(clGetKernelWorkGroupInfo) X(clGetPlatformInfo) X(clGetProgramBuildInfo) \
    X(clGetProgramInfo) X(clReleaseEvent) X(clReleaseKernel) X(clReleaseMemObject) \
    X(clReleaseProgram) X(clSetKernelArg) X(clWaitForEvents)

// One pointer per function, and a function that jumps to it with the
// caller's arguments untouched (AArch64).
#define POINTER(name) __attribute__((visibility("hidden"))) void * p_##name;
CL_FUNCTIONS(POINTER)
static void * p_clGetPlatformIDs;

#define FORWARD(name) \
    __asm__(".text\n.globl " #name "\n.type " #name ",%function\n" #name ":\n" \
            "adrp x16, p_" #name "\nldr x16, [x16, :lo12:p_" #name "]\nbr x16\n");
CL_FUNCTIONS(FORWARD)

typedef int (*get_platform_ids_fn)(unsigned, void *, unsigned *);

// CL_PLATFORM_NOT_FOUND_KHR when no driver loaded
__attribute__((visibility("default")))
int clGetPlatformIDs(unsigned n, void * platforms, unsigned * found) {
    if (p_clGetPlatformIDs == NULL) {
        if (found) *found = 0;
        return -1001;
    }
    return ((get_platform_ids_fn) p_clGetPlatformIDs)(n, platforms, found);
}

static intptr_t missing(void) { return -59; }  // CL_INVALID_OPERATION

__attribute__((constructor))
static void load(void) {
    void * (*lookup)(const char *) = NULL;
    void * lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        lib = dlopen("libOpenCL-pixel.so", RTLD_NOW | RTLD_LOCAL);
        if (lib != NULL) {
            void (*enable)(void) = (void (*)(void)) dlsym(lib, "enableOpenCL");
            lookup = (void * (*)(const char *)) dlsym(lib, "loadOpenCLPointer");
            if (enable == NULL || lookup == NULL) lib = NULL; else enable();
        }
    }
    if (lib == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "whisper", "No OpenCL driver for apps");
        return;
    }
#define RESOLVE(name) p_##name = lookup ? lookup(#name) : dlsym(lib, #name);
    CL_FUNCTIONS(RESOLVE)
    RESOLVE(clGetPlatformIDs)
    // OpenCL 2.0 drivers (Adreno 6xx) have it as cl_khr_subgroups' function
    if (p_clGetKernelSubGroupInfo == NULL) {
        p_clGetKernelSubGroupInfo = lookup ? lookup("clGetKernelSubGroupInfoKHR") : dlsym(lib, "clGetKernelSubGroupInfoKHR");
    }
    // Functions of a newer OpenCL than the driver's fail instead of crashing
#define MISSING(name) if (p_##name == NULL) { \
        p_##name = (void *) missing; \
        __android_log_print(ANDROID_LOG_INFO, "whisper", "OpenCL driver lacks %s", #name); }
    CL_FUNCTIONS(MISSING)
    __android_log_print(ANDROID_LOG_INFO, "whisper", "OpenCL driver loaded (%s)",
                        lookup ? "libOpenCL-pixel.so" : "libOpenCL.so");
}

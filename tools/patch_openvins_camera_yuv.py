#!/usr/bin/env python3
"""Fix OpenVINS Mobile's unsafe YUV_420_888 Android camera plane handling.

Original native-lib.cpp used COLOR_YUV2RGBA_NV21 for the Camera2 U plane
whenever chroma pixelStride=2. Android does NOT guarantee U-plane is NV21:
this may exchange red/blue, distort grayscale features, and corrupt RGB/DA3.

The pixelStride=1 path placed V ROI beyond its allocated yuvMat rows.
Honor Y,U,V buffers' ACTUAL row stride and pixel stride and assemble I420
bytes regardless of whether planes are planar, semiplanar, aliased or padded.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
path=root/"app/src/main/cpp/native-lib.cpp"
s=path.read_text(encoding="utf-8")
start=s.index('extern "C" JNIEXPORT jlong JNICALL Java_com_openvins_android_Camera2ResView_processYUVToRGBAJNI(')
end=s.index("// Get display image - returns raw camera",start)
body=r'''
extern "C" JNIEXPORT jlong JNICALL
Java_com_openvins_android_Camera2ResView_processYUVToRGBAJNI(
    JNIEnv *env, jclass, jbyteArray yData, jbyteArray uData,
    jbyteArray vData, jint width, jint height, jint yStride,
    jint uStride, jint vStride, jint chromaPixelStride) {
  if (yData == nullptr || uData == nullptr || vData == nullptr ||
      width < 2 || height < 2 || (width % 2) || (height % 2) ||
      yStride < width || uStride <= 0 || vStride <= 0 ||
      (chromaPixelStride != 1 && chromaPixelStride != 2))
    return 0;

  const int halfWidth = width / 2;
  const int halfHeight = height / 2;
  const jsize yLen = env->GetArrayLength(yData);
  const jsize uLen = env->GetArrayLength(uData);
  const jsize vLen = env->GetArrayLength(vData);

  // Last row often has no trailing padding in Android Image.Plane buffers.
  // Check the highest address we actually use, not capacity >= stride * h.
  const int64_t yLast = (int64_t)(height - 1) * yStride + width;
  const int64_t uLast = (int64_t)(halfHeight - 1) * uStride +
                        (halfWidth - 1) * chromaPixelStride + 1;
  const int64_t vLast = (int64_t)(halfHeight - 1) * vStride +
                        (halfWidth - 1) * chromaPixelStride + 1;
  if (yLast > yLen || uLast > uLen || vLast > vLen) {
    __android_log_print(ANDROID_LOG_ERROR,TAG,
        "YUV plane truncated: Y %d/%lld U %d/%lld V %d/%lld",
        yLen,(long long)yLast,uLen,(long long)uLast,
        vLen,(long long)vLast);
    return 0;
  }

  jbyte *y=env->GetByteArrayElements(yData,nullptr);
  if(y==nullptr)return 0;
  jbyte *u=env->GetByteArrayElements(uData,nullptr);
  if(u==nullptr){
    env->ReleaseByteArrayElements(yData,y,JNI_ABORT);
    return 0;
  }
  jbyte *v=env->GetByteArrayElements(vData,nullptr);
  if(v==nullptr){
    env->ReleaseByteArrayElements(uData,u,JNI_ABORT);
    env->ReleaseByteArrayElements(yData,y,JNI_ABORT);
    return 0;
  }

  // OpenCV I420 uses one packed YUV byte buffer. The U and V buffers
  // are independent; no NV12/NV21 interleaving assumption is made.
  cv::Mat i420(height + halfHeight,width,CV_8UC1);
  unsigned char *dst=i420.ptr<unsigned char>(0);
  const size_t yPixels=(size_t)width*height;
  const size_t chromaPixels=yPixels/4;
  for(int row=0;row<height;row++){
    memcpy(dst+(size_t)row*width,y+(size_t)row*yStride,(size_t)width);
  }
  for(int row=0;row<halfHeight;row++){
    for(int col=0;col<halfWidth;col++){
      const size_t idx=(size_t)row*halfWidth+col;
      dst[yPixels+idx]=
          static_cast<unsigned char>(u[(size_t)row*uStride+
                                         (size_t)col*chromaPixelStride]);
      dst[yPixels+chromaPixels+idx]=
          static_cast<unsigned char>(v[(size_t)row*vStride+
                                         (size_t)col*chromaPixelStride]);
    }
  }
  env->ReleaseByteArrayElements(vData,v,JNI_ABORT);
  env->ReleaseByteArrayElements(uData,u,JNI_ABORT);
  env->ReleaseByteArrayElements(yData,y,JNI_ABORT);

  cv::Mat *rgba=new cv::Mat();
  try {
    cv::cvtColor(i420,*rgba,cv::COLOR_YUV2RGBA_I420);
  } catch (const cv::Exception &e){
    __android_log_print(ANDROID_LOG_ERROR,TAG,
        "YUV_420_888 to RGB conversion failed: %s",e.what());
    delete rgba;
    return 0;
  }
  return reinterpret_cast<jlong>(rgba);
}

'''
s=s[:start]+body+s[end:]
path.write_text(s,encoding="utf-8")
print("Replaced hardcoded NV21 with stride-correct planar I420 native Camera2 RGB conversion")

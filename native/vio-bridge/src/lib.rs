//! Android JNI feasibility bridge for visloc-rs' genuine Basalt mono+IMU VIO.
//!
//! This code is intentionally NOT connected to RF Lab's production tracking
//! UI until a real Pixel 10 Pro XL has validated camera/IMU extrinsics,
//! clock offsets, pose reset behavior, performance and loop-closure error.
//! Built by .github/workflows/probe-vio-arm64.yml against a pinned upstream.
//! No camera images or IMU samples are sent to any network endpoint.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jdouble, jdoubleArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use nalgebra::Vector3;
use visloc_basalt::ImuSample;
use visloc_wasm::vio::{PhoneRig, Session, Status};

static NEXT: AtomicU64 = AtomicU64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<u64, Session>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<u64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn safe_handle(id: jlong) -> Option<u64> {
    if id <= 0 { None } else { Some(id as u64) }
}

fn open(width:jint,height:jint,fx:jdouble,fy:jdouble,
        cx:jdouble,cy:jdouble) -> Option<u64> {
    if !(width>=120 && width<=1280 && height>=120 && height<=1280
        && fx.is_finite() && fy.is_finite() && fx>50.0 && fy>50.0
        && cx.is_finite() && cy.is_finite()
        && cx>=0.0 && cx<=width as f64
        && cy>=0.0 && cy<=height as f64) { return None; }
    let rig = PhoneRig {
        width: width as u32,
        height: height as u32,
        fx,fy,cx,cy,
        distortion: [0.0; 4],
        // ASSUMES that all incoming images have already been rotated
        // to rear PORTRAIT orientation. Device-to-camera extrinsics and
        // translation below are provisional; production may not rely on
        // their scale until actual calibration is verified.
        imu_from_camera: PhoneRig::rear_portrait_camera_to_imu(
            Vector3::new(0.0,0.0,0.0)),
        imu_rate_hz: 100.0,
        accel_noise: 0.02,
        gyro_noise: 0.002,
        accel_bias_walk: 0.0001,
        gyro_bias_walk: 0.00001,
        frame_rate_hz: 30.0,
    };
    let session=Session::new(rig, vec![]).ok()?;
    let id=NEXT.fetch_add(1,Ordering::Relaxed);
    let mut registry=sessions().lock().ok()?;
    if registry.len()>=2 { return None; }
    registry.insert(id,session);
    Some(id)
}

#[no_mangle]
pub extern "system" fn Java_dev_rflab_scanner_NativeVioBridge_nativeCreate(
    _env:JNIEnv, _class:JClass,
    width:jint,height:jint,fx:jdouble,fy:jdouble,cx:jdouble,cy:jdouble
) -> jlong {
    std::panic::catch_unwind(||open(width,height,fx,fy,cx,cy))
        .ok().flatten().map_or(0,|id|id as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_rflab_scanner_NativeVioBridge_nativeImu(
    _env:JNIEnv, _class:JClass,handle:jlong,timestamp_ns:jlong,
    gx:jdouble,gy:jdouble,gz:jdouble,
    ax:jdouble,ay:jdouble,az:jdouble
) -> jboolean {
    let Some(id)=safe_handle(handle) else{return JNI_FALSE};
    if timestamp_ns<=0 ||
        ![gx,gy,gz,ax,ay,az].iter().all(|x|x.is_finite()) {
        return JNI_FALSE;
    }
    let Ok(mut map)=sessions().lock() else{return JNI_FALSE};
    let Some(vio)=map.get_mut(&id) else{return JNI_FALSE};
    let sample=ImuSample::new(timestamp_ns,
        Vector3::new(gx,gy,gz),Vector3::new(ax,ay,az));
    if vio.push_imu(sample) {JNI_TRUE} else {JNI_FALSE}
}

/// Returns null until the matching IMU time is available or on error.
/// [x,y,z,qx,qy,qz,qw,status,connected,resets,frame_id], in metres
/// ONLY after the device calibration and benchmark gates have passed.
/// Status: 0 waiting, 1 initializing, 2 tracking, 3 coasting, 4 reset.
#[no_mangle]
pub extern "system" fn Java_dev_rflab_scanner_NativeVioBridge_nativeFrame(
    env:JNIEnv, _class:JClass, handle:jlong, timestamp_ns:jlong,
    pixels:JByteArray
) -> jdoubleArray {
    let Some(id)=safe_handle(handle) else{return std::ptr::null_mut()};
    if timestamp_ns<=0 {return std::ptr::null_mut();}
    let Ok(mut map)=sessions().lock() else{return std::ptr::null_mut()};
    let Some(vio)=map.get_mut(&id) else{return std::ptr::null_mut()};
    if !vio.ready(timestamp_ns) {return std::ptr::null_mut();}
    let Ok(length)=env.get_array_length(&pixels) else{return std::ptr::null_mut()};
    if length<1 || length>2_000_000 {return std::ptr::null_mut();}
    let mut signed=vec![0_i8;length as usize];
    if env.get_byte_array_region(&pixels,0,&mut signed).is_err(){
        return std::ptr::null_mut();
    }
    let data=signed.iter().map(|v|*v as u8).collect::<Vec<u8>>();
    let Ok(update)=vio.process(timestamp_ns,&data) else{
        return std::ptr::null_mut();
    };
    let xyz=update.camera_to_world.translation;
    let rotation=update.camera_to_world.rotation;
    let q=rotation.quaternion();
    let status=match update.status {
        Status::Waiting => 0.0,
        Status::Initializing => 1.0,
        Status::Tracking => 2.0,
        Status::Coasting => 3.0,
        Status::Reset(_) => 4.0,
    };
    let array=[
        xyz.x,xyz.y,xyz.z,
        q.i,q.j,q.k,q.w,
        status,update.connected as f64,
        update.resets as f64,update.frame_id as f64,
    ];
    let Ok(result)=env.new_double_array(array.len() as jint) else {
        return std::ptr::null_mut();
    };
    if env.set_double_array_region(&result,0,&array).is_err(){
        return std::ptr::null_mut();
    }
    result.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_rflab_scanner_NativeVioBridge_nativeClose(
    _env:JNIEnv,_class:JClass,handle:jlong
) {
    let Some(id)=safe_handle(handle) else{return};
    if let Ok(mut registry)=sessions().lock() {
        registry.remove(&id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_uncalibrated_input() {
        assert!(open(0,0,0.,0.,0.,0.).is_none());
        assert!(open(480,640,f64::NAN,420.,240.,320.).is_none());
    }
}

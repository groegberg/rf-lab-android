#pragma once
#include <cmath>
#include <cstddef>

namespace rf_lab {
// The static initializer uses the older half-window's mean angular rate as bias.
template <class Samples>
bool steady_gyro(const Samples &samples, double expected_duration) {
  if (samples.size() < 2 || !std::isfinite(expected_duration) || expected_duration <= 0)
    return false;
  const double span = samples.back().timestamp - samples.front().timestamp;
  if (!std::isfinite(span) || span < 0.85 * expected_duration)
    return false;

  double integral[3] = {};
  double squared_integral = 0;
  for (std::size_t i = 1; i < samples.size(); ++i) {
    const auto &a = samples[i - 1];
    const auto &b = samples[i];
    const double dt = b.timestamp - a.timestamp;
    if (!(dt > 0 && dt <= 0.025)) return false;
    for (int axis = 0; axis < 3; ++axis) {
      const double u = a.wm[axis], v = b.wm[axis];
      integral[axis] += 0.5 * (u + v) * dt;
      squared_integral += 0.5 * (u*u + v*v) * dt;
    }
  }
  const double mean_squared = (integral[0]*integral[0] + integral[1]*integral[1] +
                               integral[2]*integral[2]) / (span*span);
  // Android calibrated gyro, rad/s. Net angular speed catches one-way turns,
  // RMS also catches reversals that average to zero.
  return mean_squared <= 0.012 * 0.012 && squared_integral / span <= 0.035 * 0.035;
}
} // namespace rf_lab

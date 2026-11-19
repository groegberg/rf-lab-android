#include "../steady_gyro.h"
#include <array>
#include <cassert>
#include <limits>
#include <vector>
struct Sample { double timestamp; std::array<double,3> wm; };
int main() {
  std::vector<Sample> samples;
  for (int k = 0; k <= 100; ++k) samples.push_back({k * .005, {.001, -.002, .003}});
  assert(rf_lab::steady_gyro(samples, .5));
  assert(!rf_lab::steady_gyro(samples, 1.0));
  for (auto &s : samples) s.wm[1] = .02;
  assert(!rf_lab::steady_gyro(samples, .5));
  for (int k = 0; k <= 100; ++k) samples[k].wm[1] = k < 50 ? .1 : -.1;
  assert(!rf_lab::steady_gyro(samples, .5));
  for (auto &s : samples) s.wm[1] = .001;
  samples[8].wm[1] = std::numeric_limits<double>::quiet_NaN();
  assert(!rf_lab::steady_gyro(samples, .5));
  samples.erase(samples.begin()+4, samples.begin()+13);
  assert(!rf_lab::steady_gyro(samples, .5));
}

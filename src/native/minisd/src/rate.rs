use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

pub struct RateLimiter {
    windows: HashMap<String, VecDeque<Instant>>,
}

impl RateLimiter {
    pub fn new() -> Self {
        Self {
            windows: HashMap::new(),
        }
    }

    pub fn check(&mut self, key: &str, rate_per_min: u32, now: Instant) -> bool {
        let window = self.windows.entry(key.to_string()).or_default();
        let horizon = Duration::from_secs(60);
        while let Some(&t) = window.front() {
            if now.duration_since(t) >= horizon {
                window.pop_front();
            } else {
                break;
            }
        }
        if window.len() as u32 >= rate_per_min {
            return false;
        }
        window.push_back(now);
        true
    }

    pub fn reset(&mut self) {
        self.windows.clear();
    }
}

impl Default for RateLimiter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_u6_rate_window() {
        let mut lim = RateLimiter::new();
        let t0 = Instant::now();
        assert!(lim.check("ubuntu.adminExec", 3, t0));
        assert!(lim.check("ubuntu.adminExec", 3, t0 + Duration::from_millis(10)));
        assert!(lim.check("ubuntu.adminExec", 3, t0 + Duration::from_millis(20)));
        assert!(!lim.check("ubuntu.adminExec", 3, t0 + Duration::from_millis(30)));
        assert!(lim.check("ubuntu.adminExec", 3, t0 + Duration::from_secs(60)));
    }
}

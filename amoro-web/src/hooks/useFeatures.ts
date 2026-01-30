export function useFeatures() {
  // TODO load feature flags from system settings

  const features = {
    '7491.enabled': 'true',
  }
  const isEnabledFeature = (feature: keyof typeof features) => features[feature] === 'true'

  return { isEnabledFeature }
}

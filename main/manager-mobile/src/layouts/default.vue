<script lang="ts" setup>
import type { ConfigProviderThemeVars } from 'wot-design-uni'

const themeVars: ConfigProviderThemeVars = {
  // colorTheme: 'red',
  // buttonPrimaryBgColor: '#07c160',
  // buttonPrimaryColor: '#07c160',
}
</script>

<template>
  <wd-config-provider :theme-vars="themeVars">
    <slot />
    <view class="wot-build-deps">
      <wd-checkbox-group>
        <wd-checkbox />
      </wd-checkbox-group>
    </view>
    <wd-toast />
    <wd-message-box />
  </wd-config-provider>
</template>

<style scoped>
.wot-build-deps {
  display: none;
}
</style>

import type { Preview } from '@storybook/vue3-vite'
import '../src/styles/storybook.less'

const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
       color: /(background|color)$/i,
       date: /Date$/i,
      },
    },
  },
};

export default preview;
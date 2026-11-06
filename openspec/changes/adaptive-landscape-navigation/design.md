## Design

Choose a side rail when the available window is at least 600 dp wide and wider than it is tall; otherwise retain bottom navigation. The rail stays outside route scrolling, preserves the five destination labels, and scrolls when the available height cannot contain them. Respect scaffold insets and IME behavior.

Allow Home to use a wider field. When at least 640 dp remains inside its instrument and font scale is at most 1.2, arrange controls and the hourly chart side by side; otherwise retain the linear stack. Semantic order remains controls before chart. Individual reading stacking and minimum touch targets remain unchanged.

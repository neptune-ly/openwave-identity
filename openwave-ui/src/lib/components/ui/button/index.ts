import Root from "./button.svelte";

export type ButtonVariant = "default" | "destructive" | "outline" | "secondary" | "ghost" | "link";
export type ButtonSize = "default" | "sm" | "lg" | "icon";

export type Props = {
	variant?: ButtonVariant;
	size?: ButtonSize;
	class?: string;
};

export { Root, Root as Button };
export type { Props as ButtonProps };


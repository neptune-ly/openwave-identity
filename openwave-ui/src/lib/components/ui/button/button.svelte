<script lang="ts">
	import type { HTMLButtonAttributes } from "svelte/elements";
	import { cn } from "$lib/utils";

	type $$Props = HTMLButtonAttributes;

	export let variant: "default" | "destructive" | "outline" | "secondary" | "ghost" | "link" = "default";
	export let size: "default" | "sm" | "lg" | "icon" = "default";
	export let type: $$Props["type"] = "button";
	let className: $$Props["class"] = undefined;
	export { className as class };

	const baseClasses =
		"inline-flex items-center justify-center rounded-md font-medium transition-colors disabled:pointer-events-none disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2";

	const sizeClasses = {
		default: "h-10 px-4 py-2",
		sm: "h-9 rounded-md px-3 text-sm",
		lg: "h-11 rounded-md px-6 text-sm",
		icon: "h-9 w-9"
	} satisfies Record<string, string>;

	const variantClasses = {
		default:
			"bg-primary text-primary-foreground hover:bg-primary/90",
		destructive:
			"bg-destructive text-destructive-foreground hover:bg-destructive/90",
		outline:
			"border border-input bg-background hover:bg-accent hover:text-accent-foreground",
		secondary:
			"bg-secondary text-secondary-foreground hover:bg-secondary/80",
		ghost:
			"hover:bg-accent hover:text-accent-foreground",
		link:
			"text-primary underline-offset-4 hover:underline"
	} satisfies Record<string, string>;

	const classes = () =>
		cn(baseClasses, variantClasses[variant], sizeClasses[size], className);
</script>

<button {type} class={classes()} { ... $$restProps }>
	<slot />
</button>


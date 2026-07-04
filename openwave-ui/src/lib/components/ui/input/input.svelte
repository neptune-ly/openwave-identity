<script lang="ts">
	import type { HTMLInputAttributes } from "svelte/elements";
	import { cn } from "$lib/utils";

	type $$Props = HTMLInputAttributes;

	let className: $$Props["class"] = undefined;
	export { className as class };
	export let value: $$Props["value"] = undefined;
	export let readonly: $$Props["readonly"] = undefined;
</script>

<input
	class={cn(
		"w-full rounded-md border border-input bg-background text-sm file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60 h-10 px-3 py-2",
		className
	)}
	bind:value
	{readonly}
	on:blur
	on:change
	on:click
	on:focus
	on:focusin
	on:focusout
	on:input
	on:keydown
	on:keypress
	on:keyup
	on:mouseover
	on:mouseenter
	on:mouseleave
	on:paste
	on:wheel|passive
	{...$$restProps}
/>


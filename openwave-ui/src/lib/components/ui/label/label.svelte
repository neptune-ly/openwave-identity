<script lang="ts">
	import type { HTMLAttributes } from "svelte/elements";
	import { cn } from "$lib/utils";

	type $$Props = HTMLAttributes<HTMLLabelElement> & {
		htmlFor?: HTMLLabelElement["htmlFor"];
	};

	let className: $$Props["class"] = undefined;
	export { className as class };
	export let htmlFor: $$Props["htmlFor"] = undefined;
</script>

<label
	for={htmlFor}
	class={cn("text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70", className)}
	{...$$restProps}
>
	<slot />
</label>

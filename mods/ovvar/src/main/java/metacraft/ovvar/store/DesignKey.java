package metacraft.ovvar.store;

import metacraft.ovvar.content.Chapter;

import java.util.UUID;

/** What a design is filed under: the player who owns it and the chapter of the ovve it is for. */
public record DesignKey(UUID owner, Chapter chapter) {
	@Override
	public String toString() {
		return owner + "/" + chapter.id;
	}
}

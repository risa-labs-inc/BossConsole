These logo-only PNG fixtures reproduce the favicon quality issue from BOSS's
separate standard and high-quality caches. They contain no browsing paths,
account information, or session data.

- Google: the standard 16px page favicon and the separate 128px host icon have
  different transparent padding.
- GitHub: the standard 16px page favicon and the separate 32px host icon use
  different monochrome theme treatments. The 128px refreshed icon was retrieved
  through the existing source, `https://www.google.com/s2/favicons?domain=github.com&sz=128`.

These fixtures test artwork matching without network access. Google and GitHub
retain ownership of their respective logos.

Home card fixtures also cover the original assets served by the sites:

- BOSS: its 16px cached icon and `/app-viewer/boss-logo.svg` from
  `https://cli.risaboss.com/`, preserving its embedded 256px PNG for the card.
- Gmail: its 16px cached icon and the 256px ICO from
  `https://mail.google.com/favicon.ico`. Its app-specific artwork must not be
  replaced with a generic Google G.

These assets contain only the public logos. BOSS and Google retain ownership.

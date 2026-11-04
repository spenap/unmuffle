# App icon concepts

Three concepts for "sound enters, meaning comes out". Each folder holds the
adaptive-icon layers (`background.svg`, `foreground.svg`, `monochrome.svg`,
108×108 dp with the art inside the 66 dp safe zone) and `full.svg`, the
composed icon as a launcher shows it (the central 72 dp, rounded square).
`preview.html` shows all three at 32–256 px, in colour and monochrome, as
circle and squircle, on light and dark.

Colours: cobalt `#1D4ED8` background, white foreground. Pills share one
weight per concept (6–7 dp, about 2 px at 32 px).

## Concepts

- **A, Bubble** (`a-bubble/`): the app's own result screen as a symbol, a
  chat bubble holding a waveform with two transcript lines under it. The
  voice message and its text in one place.
- **B, Bars to lines** (`b-bars-to-lines/`): three waveform bars on the
  left, three text lines on the right, same weight and rhythm. Read left to
  right: sound in, text out.
- **C, Settle** (`c-settle/`): a wave that calms into a straight line and
  carries on as lines of text. The muffled signal becomes plain.

## Evaluation

| | A, Bubble | B, Bars to lines | C, Settle |
|---|---|---|---|
| Recognition at 32 px | Bubble reads; bars and lines blur into texture | Best: six heavy shapes, clear gaps | Good; the wave stays visible |
| Distinctiveness | Low: chat-bubble icons are everywhere | Medium: bars and lines are common, the pairing is not | High |
| Voice-to-text | Strongest: message plus transcript | Clear once seen as left-to-right | Weakest: can read as a heartbeat or a chart |
| Simplicity | Busiest (bubble plus 7 cut-outs) | Simplest (6 pills) | Simple (3 strokes) |
| Adaptive / themed | Solid silhouette survives any mask; cut-outs thin out in monochrome | Floats freely in any mask; monochrome identical to colour | Same as B; strokes need outlining before conversion to a vector drawable |

## Chosen: A, Bubble
In the app since v1.9 (`res/drawable/ic_launcher_*.xml`), scaled to 84%
and centred on the canvas with its tail, for even padding. B was the
designer's pick for small-size clarity; A says "your message, as text"
most directly. B and C stay here as alternatives.

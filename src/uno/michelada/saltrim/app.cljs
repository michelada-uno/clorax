(ns uno.michelada.saltrim.app
  (:require
   [uno.michelada.saltrim.constants :refer [CW RH GUT HDR OVER BAR]]
   [uno.michelada.saltrim.addr :as addr])
  (:refer-clojure :exclude [meta]))

;; --- HELPERS ---

(defn ^:export json-parse-safe
  [json]
  (try (or (js/JSON.parse json) #js {})
       (catch :default _ #js {})))

(defn dispatch-custom-event
  "Dispatches a custom event. `target` can be any EventTarget (element, window, document...)"
  [target event-name & [detail]]
  (let [opts #js {:detail (or detail {})}]
    (.dispatchEvent target (js/CustomEvent. event-name opts))))

(defn dispatch-window-event
  "Dispatches a custom event on the window object."
  [event-name & [detail]]
  (dispatch-custom-event js/window event-name (or detail {})))

;; --- SCROLL ---

(def SX (atom 0))
(def SY (atom 0))

(def last-C0 (atom 0))
(def last-R0 (atom 0))

(def view-timer (atom nil))

(defn $
  [id]
  (.getElementById js/document id))

(defn meta
  []
  (let [ds (some-> ($ "meta") .-dataset)
        pj #(try (js/JSON.parse (or % "{}")) (catch :default _ {}))]
    (into {:colw (pj (:colw ds))
           :rowh (pj (:rowh ds))}
          (map (fn [[k v]] [k (js/Number v)]))
          {:tw (or (:tw ds) 1)
           :th (or (:th ds) 1)
           :cb (or (:cb ds) 0)
           :rb (or (:rb ds) 0)})))

;; --- variable axis geometry ---

(defn axis-size
  [i base ov]
  (or (get ov i) base))

(defn axis-pos
  [i base ov]
  (transduce (comp (filter #(< (js/Number %) i))
                   (map #(- % base)))
             +
             (* base i)
             ov))

(defn pixel->index
  [px base ov]
  (let [add-base-cells (fn [idx pos]
                         (+ idx (js/Math.floor (/ (- px pos) base))))]
    (loop [pos 0
           idx 0
           ks (->> ov js/Object.keys (map js/Number) sort)]
      (if (seq ks)
        (let [k (first ks)
              gap (- k idx)]
          (if (< px (+ pos (* gap base)))
            (add-base-cells idx pos)
            (let [sz (get ov k)
                  pos' (+ pos (* base gap) sz)
                  idx' (inc k)]
              (if (< px pos')
                k
                (recur pos' idx' (rest ks))))))
        (add-base-cells idx pos)))))

(defn view-size
  []
  (let [c ($ "cellclip")]
    {:w (or (some-> c .-clientWidth) 0)
     :h (or (some-> c .-clientHeight) 0)}))

(defn setT
  [id x y]
  (when-let [el ($ id)]
    (set! (-> el .-style .-transform)
          (str "translate(" x "px," y "px)"))))

(defn thumb
  [bar-id thumb-id s total vertical?]
  (let [bar ($ bar-id)
        th ($ thumb-id)]
    (when (and bar th)
      (let [vs ((if vertical? :h :w) (view-size))
            track ((if vertical? .-clientHeight .-clientWidth) bar)
            len (js/Math.max 24 (* track (js/Math.min 1 (/ vs (js/Math.max total 1)))))
            maxS (js/Math.max 1 (- total vs))
            pos (* (/ (js/Math.min s maxS) maxS) (- track len))
            [hw tl] (if vertical?
                      [.-height .-top]
                      [.-width .-left])]
        (set! (-> th .-style hw) (str len "px"))
        (set! (-> th .-style tl) (str pos "px"))))))

(defn render
  []
  (let [m (meta)
        tx (- (axis-pos (:cb m) CW (:colw m)) @SX)
        ty (- (axis-pos (:rb m) RH (:rowh m)) @SY)]
    (setT "cells" tx ty)
    (setT "self" tx ty)
    (setT "peers" tx ty)
    (setT "editlayer" tx ty)
    (setT "colstrip" tx 0)
    (setT "rowstrip" 0 ty)
    (thumb "vbar" "vthumb" @SY (:th m) true)
    (thumb "hbar" "hthumb" @SX (:tw m) false)))

(defn clamp-scroll
  []
  (let [m (meta)
        vs (view-size)
        clamp! (fn [S vertical?]
                (swap! S #(js/Math.max 0 (js/Math.min % (js/Math.max 0 (- ((if vertical? :th :tw) m)
                                                                          ((if vertical? :h :w) vs)))))))]
    (clamp! SX false)
    (clamp! SY true)))

(defn request-view
  [force?]
  (let [m (meta)
        c0 (pixel->index @SX CW (:colw m))
        r0 (pixel->index @SY RH (:rowh m))]
    (when (or force? (not= c0 @last-C0) (not= r0 @last-R0))
      (reset! last-C0 c0)
      (reset! last-R0 r0)
      (js/clearTimeout @view-timer)
      (reset! view-timer
              (js/setTimeout #(dispatch-window-event "view-trigger" {:r0 r0 :c0 c0}) 70)))))

(defn on-wheel
  [e]
  (.preventDefault e)
  (swap! SX #(+ % (.-deltaX e)))
  (swap! SY #(+ % (.-deltaY e)))
  (clamp-scroll)
  (render)
  (request-view false))

(defn ^:export drag-thumb
  [e vertical?]
  (.preventDefault e)
  (let [th    (.-target e)
        bar   (.-parentNode e)
        start ((if vertical? .-clientY .-clientX) e)
        t0    (let [prop (if vertical? .-top .-left)]
                (js/parseFloat (or (-> th .-style prop) 0)))
        track ((if vertical? .-clientHeight .-clientWidth) bar)
        len   ((if vertical? .-clientHeight .-clientWidth) th)
        m     (meta)
        vs    (view-size)
        total ((if vertical? :th :tw) m)
        vsz   ((if vertical? :h :w) vs)
        maxS  (js/Math.max 1 (- total vsz))]
    (letfn [(mm [e2]
              (let [cur ((if vertical? .-clientY .-clientX) e2)
                    pos (js/Math.max 0 (js/Math.min (+ t0 (- cur start)) (- track len)))
                    s   (* (/ pos (js/Math.max 1 (- track len))) maxS)]
                (reset! (if vertical? SY SX) s)
                (clamp-scroll)
                (render)
                (request-view false)))
            (mu []
              (.removeEventListener js/document "mousemove" mm)
              (.removeEventListener js/document "mouseup" mu))]
      (.addEventListener js/document "mousemove" mm)
      (.addEventListener js/document "mouseup" mu))))

;; --- selection (server-rendered #self) ---

(defn ^:export jump
  [addr]
  (when-let [p (addr/parse addr)]
    (let [m (meta)]
      (reset! SX (axis-pos (:ci p) CW (:colw m)))
      (reset! SY (axis-pos (:ri p) RH (:rowh m)))
      (dispatch-window-event "jump" {:addr addr
                                     :callback (fn []
                                                 (render)
                                                 (request-view true))}))))

(defn ensure-visible
  [addr]
  (when-let [p (addr/parse addr)]
    (let [m  (meta)
          vs (view-size)
          x  (axis-pos (:ci p) CW (:colw m))
          y  (axis-pos (:ri p) RH (:rowh m))
          w  (axis-size (:ci p) CW (:colw m))
          h  (axis-size (:ri p) RH (:rowh m))]
      (cond
        (< x @SX)                   (reset! SX x)
        (< (+ @SX (:w vs)) (+ x w)) (reset! SX (- (+ x w) (:w vs))))
      (cond
        (< y @SY)                   (reset! SY y)
        (< (+ @SY (:h vs)) (+ y h)) (reset! SY (- (+ y h) (:h vs))))
      (letfn [(swap-pos! [a] (swap! a #(js/Math.max 0 %)))]
        (swap-pos! SX)
        (swap-pos! SY))
      (render)
      (request-view false))))

;; --- EDITOR ---

(defn ^:export editor-event-keydown
  [e]
  (let [key (.-key e)
        editor (.-target e)]
    (when (#{"Enter" "Escape"} key)
      (.preventDefault e)
      (.stopPropagation e)
      (case key
        "Enter" (dispatch-custom-event editor "commit-edit")
        "Escape" (dispatch-custom-event editor "cancel-edit")))))

(defn ^:export editor-event-commit-edit
  [edit?]
  (when edit?
    (dispatch-window-event "cell-trigger")
    (dispatch-window-event "select-trigger")))

(defn ^:export editor-event-cancel-edit
  [edit?]
  (when edit?
    (dispatch-window-event "select-trigger")))

(defn start-edit
  [addr]
  (when-let [p (addr/parse addr)]
    ))

#!/usr/bin/env ruby
# frozen_string_literal: true
#
# Verify that every external `uses:` reference in .github/workflows/*.yml and
# .github/actions/**/*.yml is pinned to a full-length commit SHA with the
# original tag kept as a trailing comment, e.g.
#     uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
#
# A tag ref (`@v7`) can be retargeted by whoever controls the action's
# repository, so an unpinned or tag-pinned line is a supply-chain hole, and a
# bare SHA without its `# vX` comment is un-auditable. Local (`./...`) and
# docker (`docker://...`) refs are not remote actions and are exempt.
#
# This is a real YAML scan, not a line scan: Psych parses each file and every
# mapping pair whose *decoded* key is "uses" is inspected, which catches
# quoted and escape-encoded keys such as "us\u0065s" in both block and flow
# mappings. Anything a uses value can legally be besides a plain/quoted scalar
# (mapping, sequence, alias, literal/folded block scalar) is deliberately
# reported as UNSUPPORTED and fails the check - we never reinterpret it.
# `uses:`-looking text inside `run:` block scalars or comments is ordinary
# scalar content here and is never inspected. A file that fails to parse, or
# a tree where no external uses reference was found at all, fails closed.
#
# Known limitation: a `uses` key that is not an action reference (for example
# a `with:` input literally named `uses`) is checked as if it were one.
#
# Usage:
#   bash scripts/test/test-action-sha-pins.sh              # check the repo tree
#   bash scripts/test/test-action-sha-pins.sh --self-test  # run fixture tests
require 'psych'
require 'tmpdir'
require 'fileutils'

REPO_ROOT = File.expand_path('../..', __dir__)

SHA_RE = /\A[A-Za-z0-9][A-Za-z0-9_.-]*\/[A-Za-z0-9][A-Za-z0-9_.\/-]*@[0-9a-fA-F]{40}\z/

# Psych returns Integer style constants on some versions and Symbols on
# others; normalise via the class constants.
LITERAL_FOLDED = [Psych::Nodes::Scalar::LITERAL, Psych::Nodes::Scalar::FOLDED,
                  :literal, :folded].freeze
FLOW = [Psych::Nodes::Mapping::FLOW, :flow].freeze

Results = Struct.new(:violations, :external, :local, :docker, :files, keyword_init: true) do
  def initialize(**)
    super
    self.violations ||= []
    self.external ||= 0
    self.local ||= 0
    self.docker ||= 0
    self.files ||= 0
  end
end

# A comment tail is only the exact source text immediately after a node's
# end boundary: optional horizontal whitespace then a non-empty `#` comment.
# Anchoring on the node boundary means apostrophes or `#` inside later flow
# sibling values can never be mistaken for the uses comment.
COMMENT_TAIL = /\A[ \t]*(#[ \t]*\S.*)/

def scalar_line_tail(lines, node)
  line = lines[node.end_line]
  return '' if line.nil?
  line = line.chomp
  col = [node.end_column, line.length].min
  line[col..] || ''
end

def tail_comment(lines, node)
  m = scalar_line_tail(lines, node).match(COMMENT_TAIL)
  m && m[1]
end

# The original-ref comment must trail the uses scalar itself, or - when the
# value sits inside a flow collection - trail that collection's closing
# bracket (`- {uses: x@sha} # v7`, including multiline flows). Comments on
# later lines of an enclosing *block* mapping belong to sibling entries and
# do not count.
def uses_comment(lines, val_node, flow_ancestor)
  tail_comment(lines, val_node) ||
    (flow_ancestor && tail_comment(lines, flow_ancestor))
end

def record_violation(results, file, node, msg)
  results.violations << "#{file}:#{node.start_line + 1} #{msg}"
end

def validate_uses_value(results, file, key_node, val_node, lines, flow_ancestor)
  case val_node
  when Psych::Nodes::Scalar
    if LITERAL_FOLDED.include?(val_node.style)
      record_violation(results, file, key_node,
        "unsupported uses scalar style #{val_node.style} (use a plain value)")
      return
    end
    v = val_node.value.to_s.strip
    if v.empty?
      record_violation(results, file, key_node, 'empty uses value')
      return
    end
    if v =~ SHA_RE
      cmt = uses_comment(lines, val_node, flow_ancestor)
      if cmt && cmt =~ /\A#[ \t]*\S/
        results.external += 1
        puts "PIN #{file}:#{val_node.start_line + 1} #{v} #{cmt}"
      else
        record_violation(results, file, key_node,
          "SHA pin lacks the original-ref comment: #{v}")
      end
    elsif v.start_with?('./')
      results.local += 1
      puts "LOCAL #{file}:#{val_node.start_line + 1} #{v}"
    elsif v.start_with?('docker://')
      results.docker += 1
      puts "DOCKER #{file}:#{val_node.start_line + 1} #{v}"
    elsif v.include?('@')
      record_violation(results, file, key_node,
        "external action not SHA-pinned: #{v}")
    elsif v.include?('/')
      record_violation(results, file, key_node,
        "external action has no pin ref: #{v}")
    else
      record_violation(results, file, key_node, "unrecognised uses value: #{v}")
    end
  when Psych::Nodes::Mapping
    record_violation(results, file, key_node, 'uses value is a mapping')
  when Psych::Nodes::Sequence
    record_violation(results, file, key_node, 'uses value is a sequence')
  when Psych::Nodes::Alias
    record_violation(results, file, key_node, 'uses value is an alias')
  else
    record_violation(results, file, key_node,
      "unsupported uses value node #{val_node.class}")
  end
end

def walk(node, file, lines, results)
  case node
  when Psych::Nodes::Mapping
    flow_ancestor = FLOW.include?(node.style) ? node : nil
    node.children.each_slice(2) do |key, val|
      if key.is_a?(Psych::Nodes::Alias)
        # `*k: value` resolves through an anchor we do not evaluate - it could
        # be `uses`, so fail closed rather than silently skipping the pair.
        record_violation(results, file, key, 'unsupported: alias used as mapping key')
      elsif key.is_a?(Psych::Nodes::Scalar) && key.value == 'uses'
        validate_uses_value(results, file, key, val, lines, flow_ancestor)
      else
        walk(key, file, lines, results)
      end
      walk(val, file, lines, results)
    end
  when Psych::Nodes::Sequence
    node.children.each { |c| walk(c, file, lines, results) }
  when Psych::Nodes::Document
    walk(node.root, file, lines, results)
  end
end

def scan_file(path, results)
  src = File.read(path)
  begin
    stream = Psych.parse_stream(src)
  rescue Psych::Exception => e
    results.violations << "#{path}: YAML parse error: #{e.message.lines.first.strip}"
    return
  end
  lines = src.lines
  stream.children.each { |doc| walk(doc, path, lines, results) }
end

def yaml_files(root)
  files = []
  wf = File.join(root, '.github/workflows')
  af = File.join(root, '.github/actions')
  files.concat(Dir.glob(File.join(wf, '*.{yml,yaml}'))) if File.directory?(wf)
  files.concat(Dir.glob(File.join(af, '**/*.{yml,yaml}'))) if File.directory?(af)
  files.sort
end

def check_root(root)
  results = Results.new
  files = yaml_files(root)
  if files.empty?
    warn "ERROR: no workflow/action files found under #{root}/.github"
    return 1
  end
  results.files = files.length
  files.each { |f| scan_file(f, results) }

  results.violations.each { |v| puts "VIOLATION #{v}" }
  puts '---'
  puts "Scanned #{results.files} files: #{results.external} external SHA-pinned, " \
       "#{results.local} local, #{results.docker} docker, #{results.violations.length} violations"

  if results.external.zero?
    warn 'ERROR: zero external uses references inspected - scanner failure suspected'
    return 1
  end
  return 1 unless results.violations.empty?

  puts 'OK: all external uses references are SHA-pinned'
  0
end

# --------------------------------------------------------------------------
# Self-test fixtures
# --------------------------------------------------------------------------
SHA = '3d3c42e5aac5ba805825da76410c181273ba90b1'

def fixture(dir, name, body)
  path = File.join(dir, name)
  FileUtils.mkdir_p(File.join(path, '.github/workflows'))
  File.write(File.join(path, '.github/workflows/w.yml'), body)
  path
end

def run_check(dir)
  # capture stdout/stderr of check_root in-process
  require 'stringio'
  out = StringIO.new
  err = StringIO.new
  orig_o, orig_e = $stdout, $stderr
  $stdout, $stderr = out, err
  rc = check_root(dir)
  $stdout, $stderr = orig_o, orig_e
  [rc, out.string + err.string]
end

def self_test
  failures = 0
  check = lambda do |name, dir, want_rc, want_see = nil, want_absent = nil|
    rc, out = run_check(dir)
    ok = (want_rc == :pass ? rc.zero? : rc != 0)
    ok &&= want_see.all? { |t| out.include?(t) } if want_see
    ok &&= want_absent.none? { |t| out.include?(t) } if want_absent
    if ok
      puts "  ok: #{name}"
    else
      warn "  FAIL: #{name} (rc=#{rc})"
      warn out
      failures += 1
    end
  end

  Dir.mktmpdir do |dir|
    puts '== flow mappings containing uses =='
    d = fixture(dir, 'f1', <<~YML)
      steps:
        - { uses: actions/checkout@v7 }
    YML
    check.call('flow_seq_item_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'f2', <<~YML)
      steps:
        - { uses: actions/checkout@#{SHA} } # v7
    YML
    check.call('flow_seq_item_sha', d, :pass)

    d = fixture(dir, 'f3', <<~YML)
      steps: [{uses: actions/checkout@v7}]
    YML
    check.call('flow_steps_inline_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'f4', <<~YML)
      steps: [[uses: actions/checkout@v7]]
    YML
    check.call('flow_implicit_pair_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'f5', <<~YML)
      steps:
        - { uses: actions/checkout@#{SHA},
            with: { fetch-depth: 0 } } # v7
    YML
    check.call('flow_multiline_sha', d, :pass)

    puts '== escaped keys (block and flow) =='
    d = fixture(dir, 'e1', <<~'YML')
      steps:
        - "us\u0065s": actions/checkout@v7
    YML
    check.call('escaped_key_block_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'e2', <<~'YML')
      steps:
        - { "us\u0065s": actions/checkout@v7 }
    YML
    check.call('escaped_key_flow_tag', d, :fail, ['actions/checkout@v7'])

    # 'us<U+200C>es' decodes to a key that is not exactly "uses"
    d = fixture(dir, 'e3', <<~YML)
      steps:
        - "us‌es": not-a-key
        - uses: actions/checkout@#{SHA} # v7
    YML
    check.call('quoted_decoy_key_pass', d, :pass)

    puts '== multiline / empty uses =='
    d = fixture(dir, 'm1', <<~YML)
      steps:
        - uses:
            actions/checkout@v7
    YML
    check.call('multiline_uses_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'm2', <<~YML)
      steps:
        - uses:
            actions/checkout@#{SHA} # v7
    YML
    check.call('multiline_uses_sha', d, :pass)

    d = fixture(dir, 'm3', <<~YML)
      steps:
        - uses:
          with:
            fetch-depth: 0
    YML
    check.call('empty_uses_null', d, :fail)

    d = fixture(dir, 'm4', <<~YML)
      steps:
        - uses:
    YML
    check.call('empty_uses_eof', d, :fail)

    d = fixture(dir, 'm5', <<~YML)
      steps: [{uses: }]
    YML
    check.call('empty_uses_flow', d, :fail)

    puts '== literal/folded uses styles rejected =='
    d = fixture(dir, 'b1', <<~YML)
      steps:
        - uses: >
            actions/checkout@#{SHA} # v7
    YML
    check.call('folded_uses_rejected', d, :fail)

    d = fixture(dir, 'b2', <<~YML)
      steps:
        - uses: |
            actions/checkout@#{SHA} # v7
    YML
    check.call('literal_uses_rejected', d, :fail)

    puts '== misleading comment text with colon/pipe =='
    d = fixture(dir, 'c1', <<~YML)
      steps:
        - name: do the thing  # example: |
          uses: actions/checkout@v7
    YML
    check.call('comment_pipe_trap_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'c2', <<~YML)
      steps:
        - name: do the thing  # example: |
          uses: actions/checkout@#{SHA} # v7
    YML
    check.call('comment_pipe_trap_sha', d, :pass)

    d = fixture(dir, 'c3', <<~YML)
      # example: |
      #   uses: fake/action@v1
      steps:
        - uses: actions/checkout@#{SHA} # v7
    YML
    check.call('pure_comment_pipe', d, :pass, nil, ['fake/action'])

    puts '== header comments =='
    d = fixture(dir, 'h1', <<~YML)
      # uses: fake/action@v1
      # run: |
      #   uses: also/fake@v2
      steps:
        - uses: actions/checkout@#{SHA} # v7
    YML
    check.call('header_comments_ignored', d, :pass, nil, ['fake/action', 'also/fake'])

    puts '== run block scalars: uses-like text and tabs ignored =='
    # |2- block scalar content: uses-like text and interior tabs are literal
    d = fixture(dir, 'r1', "steps:\n  - run: |2-\n      echo \"uses:	fake/action@v1\"\n      uses:	still-inside\n    uses: actions/checkout@#{SHA} # v7\n")
    check.call('run_block_indented_uses_ignored', d, :pass, nil, ['fake/action', 'still-inside'])

    d = fixture(dir, 'r2', "steps:\n  - run: |\n      echo uses: fake/action@v1\n      uses: notreal\n    uses: actions/checkout@#{SHA} # v7\n")
    check.call('run_block_plain_uses_ignored', d, :pass, nil, ['fake/action', 'notreal'])

    puts '== quoted keys and values =='
    d = fixture(dir, 'q1', <<~'YML')
      steps:
        - "uses": actions/checkout@v7
    YML
    check.call('dquoted_key_tag', d, :fail, ['actions/checkout@v7'])

    d = fixture(dir, 'q2', <<~YML)
      steps:
        - 'uses': actions/checkout@#{SHA} # v7
    YML
    check.call('squoted_key_sha', d, :pass)

    d = fixture(dir, 'q3', <<~YML)
      steps:
        - uses: "actions/checkout@#{SHA}" # v7
    YML
    check.call('quoted_value_sha', d, :pass)

    d = fixture(dir, 'q4', <<~'YML')
      steps:
        - uses: 'actions/checkout@v7'
    YML
    check.call('quoted_value_tag', d, :fail)

    d = fixture(dir, 'q5', <<~YML)
      steps:
        - uses: "actions/checkout@#{SHA}"
    YML
    check.call('quoted_value_no_comment', d, :fail)

    puts '== comment on pins required =='
    d = fixture(dir, 'p1', <<~YML)
      steps:
        - uses: actions/checkout@#{SHA}
    YML
    check.call('sha_without_comment', d, :fail)

    d = fixture(dir, 'p2', <<~YML)
      steps:
        - uses: actions/checkout@#{SHA} #
    YML
    check.call('sha_empty_comment', d, :fail)

    d = fixture(dir, 'p3', "steps:\n  - uses: actions/checkout@#{SHA} #   \n")
    check.call('sha_blank_comment', d, :fail)

    d = fixture(dir, 'p4', <<~YML)
      steps:
        - uses: actions/checkout@#{SHA} # v7
    YML
    check.call('sha_with_comment', d, :pass)

    puts '== local / docker exempt =='
    d = fixture(dir, 'l1', <<~YML)
      jobs:
        call:
          uses: ./.github/workflows/reusable.yml
      steps:
        - uses: ./local/action
        - uses: docker://alpine:3.19
        - uses: actions/checkout@#{SHA} # v7
    YML
    check.call('local_docker_exempt', d, :pass)

    puts '== unpinned variants must fail =='
    d = fixture(dir, 'u1', "steps:\n  - uses: actions/checkout\n")
    check.call('no_ref_fails', d, :fail)
    d = fixture(dir, 'u2', "steps:\n  - uses: actions/checkout@main\n")
    check.call('branch_ref_fails', d, :fail)
    d = fixture(dir, 'u3', "steps:\n  - uses: actions/checkout@v7\n")
    check.call('tag_ref_fails', d, :fail)

    puts '== unsupported uses shapes =='
    d = fixture(dir, 'x1', <<~YML)
      steps:
        - uses:
            - a
            - b
    YML
    check.call('uses_sequence_rejected', d, :fail)

    d = fixture(dir, 'x2', <<~YML)
      steps:
        - anchor: &a actions/checkout@v7
        - uses: *a
    YML
    check.call('uses_alias_rejected', d, :fail)

    d = fixture(dir, 'x3', "steps:\n  - uses: actions/checkout@#{SHA} # v7\n  broken: [unclosed\n")
    check.call('parse_error_fails', d, :fail)

    puts '== alias mapping keys fail closed =='
    d = fixture(dir, 'a1', <<~YML)
      vars:
        k: &k uses
      steps:
        - *k: actions/checkout@v7
    YML
    check.call('alias_key_block', d, :fail)

    d = fixture(dir, 'a2', <<~YML)
      vars:
        k: &k uses
      steps:
        - { *k: actions/checkout@v7 }
    YML
    check.call('alias_key_flow', d, :fail)

    d = fixture(dir, 'a3', <<~YML)
      vars:
        k: &k something-else
      steps:
        - *k: actions/checkout@#{SHA} # v7
        - uses: actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c # v6.0.0
    YML
    check.call('alias_key_any_fails', d, :fail)

    puts '== apostrophes in sibling flow values cannot fake comments =='
    d = fixture(dir, 'q6', <<~'YML')
      steps:
        - { uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1, note: "it's a # trap" }
    YML
    check.call('apostrophe_no_comment', d, :fail)

    d = fixture(dir, 'q7', <<~'YML')
      steps:
        - { uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1, note: 'it''s # fake' }
    YML
    check.call('squoted_apostrophe_no_comment', d, :fail)

    d = fixture(dir, 'q8', <<~'YML')
      steps:
        - { uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1, note: "don't" } # v7
    YML
    check.call('apostrophe_with_real_comment', d, :pass)

    puts '== nested composite actions scanned =='
    d = File.join(dir, 'nested')
    FileUtils.mkdir_p(File.join(d, '.github/workflows'))
    FileUtils.mkdir_p(File.join(d, '.github/actions/nested/deeper'))
    File.write(File.join(d, '.github/workflows/w.yml'), "steps:\n  - uses: actions/checkout@#{SHA} # v7\n")
    File.write(File.join(d, '.github/actions/nested/deeper/action.yml'),
               "runs:\n  using: composite\n  steps:\n    - uses: actions/cache@v6\n")
    check.call('nested_composite_tag', d, :fail, ['actions/cache@v6'])

    puts '== real tree sanity =='
    rc, out = run_check(REPO_ROOT)
    pins = out.scan(/^PIN /).length
    if pins >= 1
      puts "  ok: real tree has #{pins} external SHA-pinned refs"
    else
      warn "  FAIL: real tree produced #{pins} external pins"
      warn out
      failures += 1
    end
  end

  puts '---'
  if failures.positive?
    warn "self-test: #{failures} fixture(s) failed"
    return 1
  end
  puts 'self-test: all fixtures passed'
  0
end

case ARGV[0]
when '--self-test' then exit self_test
when nil then exit check_root(REPO_ROOT)
else exit check_root(ARGV[0])
end
